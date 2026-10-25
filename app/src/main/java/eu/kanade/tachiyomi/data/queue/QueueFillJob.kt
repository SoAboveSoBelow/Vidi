package eu.kanade.tachiyomi.data.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import aniyomi.domain.queue.interactor.AddToQueue
import aniyomi.domain.queue.model.QueueBlock
import aniyomi.domain.queue.model.QueueFillState
import aniyomi.domain.queue.model.QueueItem
import aniyomi.domain.queue.repository.QueueRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import java.util.concurrent.TimeUnit

// AM (PLAYBACK_QUEUE) -->
/**
 * Fills queue blocks whose episodes were not in the database when they were
 * queued - a browse result, which nothing has fetched.
 *
 * Deliberately a worker rather than work done inline by whatever queued the
 * entry. Queueing a selection of twenty browse results is twenty source
 * fetches; doing that in a ViewModel scope ties it to a screen the user is
 * about to leave, and gives it none of the retry, backoff or network
 * constraints that an operation over someone else's server needs. [LibraryUpdateJob]
 * already solves exactly this problem for the library sweep, so the shape here
 * is borrowed from it rather than reinvented:
 *
 *  - fetching goes through [UpdateAnimeFromRemote.awaitEpisodesUpdate], which is the
 *    same call the library sweep uses, so episodes land in the database by the
 *    same path with the same dedupe and mark handling. Rate limiting needs
 *    nothing here: it lives in each extension's own OkHttp client
 *    (RateLimitInterceptor), and going through the source reaches it.
 *  - blocks are grouped by source and each source's run holds a permit from a
 *    [Semaphore], so one extension is never hit by several parallel fetches.
 *  - retry is WorkManager's, not hand-rolled: a failed run returns
 *    [Result.retry] and the backoff below decides when.
 *
 * The one deliberate difference is the backoff interval. The library sweep uses
 * ten minutes, which suits a nightly job nobody is waiting on; someone who just
 * pressed "add to queue" is waiting, so this starts at thirty seconds.
 *
 * Pending blocks are read from the database rather than passed in as input
 * data, so a run started after process death picks up whatever is still
 * outstanding instead of needing to have been told.
 */
class QueueFillJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var queueRepository: QueueRepository

    @Inject private lateinit var getAnime: GetAnime

    @Inject private lateinit var addToQueue: AddToQueue

    @Inject private lateinit var updateAnimeFromRemote: UpdateAnimeFromRemote

    override suspend fun doWork(): Result {
        graph.inject(this)

        val pending = queueRepository.getBlocksByFillState(QueueFillState.PENDING)
        if (pending.isEmpty()) return Result.success()

        return withIOContext {
            val failed = fill(pending)
            // Retried as a whole rather than per block: the blocks that
            // succeeded are COMPLETE in the database now, so the next run
            // reads only what is still pending and nothing is refetched.
            if (failed) Result.retry() else Result.success()
        }
    }

    /** Returns whether any block failed in a way worth retrying. */
    private suspend fun fill(blocks: List<QueueBlock>): Boolean = coroutineScope {
        val semaphore = Semaphore(SOURCE_CONCURRENCY)

        val resolved = blocks.mapNotNull { block ->
            val animeId = block.animeId
            if (animeId == null) {
                // A block with no entry behind it has nothing to fetch, so it
                // was never really pending. Settle it rather than leaving it to
                // be picked up by every future run.
                queueRepository.setBlockFillState(block.id, QueueFillState.COMPLETE)
                return@mapNotNull null
            }
            val anime = getAnime.await(animeId)
            if (anime == null) {
                // The entry was deleted between queueing and now. The block's
                // items would have cascaded with it; mark it so it stops being
                // retried, and leave it in place rather than silently closing
                // the gap in the user's order.
                queueRepository.setBlockFillState(block.id, QueueFillState.FAILED)
                return@mapNotNull null
            }
            block to anime
        }

        resolved
            .groupBy { (_, anime) -> anime.source }
            .values
            .map { blocksInSource ->
                async {
                    semaphore.withPermit {
                        blocksInSource.count { (block, anime) ->
                            ensureActive()
                            !fillBlock(block, anime)
                        }
                    }
                }
            }
            .awaitAll()
            .sum() > 0
    }

    /** Returns whether [block] was filled. */
    private suspend fun fillBlock(block: QueueBlock, anime: Anime): Boolean {
        return try {
            updateAnimeFromRemote
                .awaitEpisodesUpdate(anime = anime, fetchEpisodes = true)
                .getOrThrow()

            // Read the order back rather than using the fetch's own return
            // value: what belongs in the queue is the entry's episodes in play
            // order, which for a merged entry spans children the fetch never
            // touched, and which has the entry's sort applied. AddToQueue.orderFor
            // is the one answer to that, shared with the add path so a filled
            // block and an immediately-complete one cannot be ordered
            // differently.
            val episodes = addToQueue.orderFor(anime)
            if (episodes.isEmpty()) {
                // A source that genuinely has no episodes for this entry. Not
                // an error and not worth retrying - the block stays empty and
                // stops being pending.
                queueRepository.setBlockFillState(block.id, QueueFillState.COMPLETE)
                return true
            }

            queueRepository.replaceBlockItems(
                blockId = block.id,
                items = episodes.map { QueueItem.New.library(animeId = anime.id, episodeId = it.id) },
            )
            queueRepository.setBlockFillState(block.id, QueueFillState.COMPLETE)
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "QUEUE_FILL failed for ${anime.title}" }
            false
        }
    }

    companion object {
        private const val TAG = "QueueFill"

        /**
         * Matches the library sweep's own limit: one permit per source, five at
         * a time overall.
         */
        private const val SOURCE_CONCURRENCY = 5

        /**
         * Starts a fill run.
         *
         * [ExistingWorkPolicy.APPEND_OR_REPLACE] rather than KEEP: a run
         * already in flight has read its list of pending blocks, so anything
         * queued after it started would be missed by KEEP and never looked at
         * again. Plain APPEND would chain correctly but carries a cancelled or
         * failed predecessor onto the appended work; the OR_REPLACE variant
         * starts fresh in exactly that case, which is what a queue the user
         * has just added to needs.
         */
        fun start(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<QueueFillJob>()
                .addTag(TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
// <-- AM (PLAYBACK_QUEUE)
