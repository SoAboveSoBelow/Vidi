// AM (MERGED_SOURCES) -->
package eu.kanade.domain.anime.interactor

import aniyomi.domain.merge.interactor.SyncMergedEntryInfo
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.MergedSource
import java.util.UUID
import kotlin.time.Clock
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories

/**
 * Creates and grows merged entries: an [Anime] row with `source ==
 * [MergedSource.ID]` and no episodes of its own, whose children (recorded in
 * the `merge_children` join table, each child keeping its own real source,
 * season parent, and favorite state) are unioned together by
 * [aniyomi.domain.merge.interactor.GetMergedEpisodeList].
 *
 * Membership is many-to-many: the same entry may contribute to any number of
 * merges, and an entry that's already a season child or already in another
 * merge is still mergeable. The only hard rule is no nesting - a merge parent
 * can never become a child of another merge, because episode resolution and
 * refresh both assume children are real source-backed entries.
 *
 * Children are deliberately left favorited: removing the originals from the
 * library is a separate post-merge choice the UI offers, which also means
 * LibraryUpdateJob's normal favorites sweep keeps their episodes fresh
 * without any special wiring.
 */
@Inject
class MergeAnimeEntries(
    private val animeRepository: AnimeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val getCategories: GetCategories,
    private val setAnimeCategories: SetAnimeCategories,
    // AM (MERGE_SETTINGS) -->
    private val syncMergedEntryInfo: SyncMergedEntryInfo,
    // <-- AM (MERGE_SETTINGS)
) {

    // AM (MERGE_EXISTING) -->
    /**
     * Merges a library selection that may itself contain merged entries.
     *
     * - No merged entry selected: a new merge, as before.
     * - Exactly one: the other entries are added to it, keeping its existing
     *   order, seasons and settings. Ones already in it are skipped.
     * - Two or more: a NEW merged entry holding every source between them,
     *   each merge contributing its sources in its own priority order and the
     *   groups following the selection order - one list on top of the next.
     *   The selected entries are left alone; a source can belong to any number
     *   of merges, so nothing has to be taken apart.
     *
     * A source reached through more than one selected entry is kept once, at
     * its earliest position.
     */
    suspend fun mergeSelection(orderedSelection: List<Anime>): Anime {
        require(orderedSelection.size >= 2) {
            "Need at least 2 entries to merge"
        }
        val mergeParents = orderedSelection.filter { it.source == MergedSource.ID }
        return when (mergeParents.size) {
            0 -> createMerge(orderedSelection)
            1 -> {
                val parent = mergeParents.single()
                val existing = mergeChildRepository.getChildrenByMergeParentId(parent.id)
                    .mapTo(HashSet()) { it.id }
                orderedSelection
                    .filter { it.source != MergedSource.ID && it.id !in existing }
                    .forEach { addToMerge(parent, it) }
                parent
            }
            else -> createMerge(expandToSources(orderedSelection))
        }
    }

    /** Merged entries become their sources, in priority order; everything else is itself. */
    private suspend fun expandToSources(selection: List<Anime>): List<Anime> {
        return selection
            .flatMap {
                if (it.source == MergedSource.ID) {
                    mergeChildRepository.getChildrenByMergeParentId(it.id)
                } else {
                    listOf(it)
                }
            }
            .distinctBy { it.id }
    }
    // <-- AM (MERGE_EXISTING)

    /**
     * Creates a new merged entry from [orderedAnime], in the order given - that
     * order becomes each source's priority (`merge_children.priority`) for
     * [aniyomi.domain.merge.interactor.GetMergedEpisodeList]'s gap-fill/override
     * resolution and for display order generally.
     */
    suspend fun createMerge(orderedAnime: List<Anime>): Anime {
        require(orderedAnime.size >= 2) {
            "Need at least 2 entries to create a merge"
        }
        require(orderedAnime.none { it.source == MergedSource.ID }) {
            "Can't nest a merged entry inside another merge"
        }

        val first = orderedAnime.first()
        val parent = animeRepository.insertNetworkAnime(
            listOf(
                Anime.create().copy(
                    source = MergedSource.ID,
                    url = "merge://${UUID.randomUUID()}",
                    ogTitle = first.title,
                    thumbnailUrl = first.thumbnailUrl,
                    favorite = true,
                    initialized = true,
                    dateAdded = Clock.System.now().toEpochMilliseconds(),
                ),
            ),
        ).single()

        // Union of every source's categories, so the merged entry doesn't
        // silently disappear from whichever category tab you expected to
        // find it under - a source with none assigned contributes nothing.
        val categoryIds = orderedAnime
            .flatMap { getCategories.await(it.id) }
            .map { it.id }
            .distinct()
        if (categoryIds.isNotEmpty()) {
            setAnimeCategories.await(parent.id, categoryIds)
        }

        orderedAnime.forEachIndexed { index, anime ->
            // All children start in the single default season - one flat list.
            mergeChildRepository.addChild(
                parent.id,
                anime.id,
                index.toLong(),
                seasonNumber = MERGE_DEFAULT_SEASON_NUMBER,
            )
        }
        // AM (MERGE_SETTINGS) -->
        // Details beyond title and cover, and tags from every source.
        syncMergedEntryInfo.await(parent.id)
        // <-- AM (MERGE_SETTINGS)
        return parent
    }

    /**
     * Adds [anime] to an existing merge, always appended last in priority order
     * (max existing priority + 1) and in the shared default season - never
     * reshuffles the sources already there.
     */
    suspend fun addToMerge(mergeParent: Anime, anime: Anime) {
        require(mergeParent.source == MergedSource.ID) {
            "Target is not a merged entry"
        }
        require(anime.source != MergedSource.ID) {
            "Can't nest a merged entry inside another merge"
        }
        val existingChildren = mergeChildRepository.getChildrenByMergeParentId(mergeParent.id)
        require(existingChildren.none { it.id == anime.id }) {
            "Entry is already part of this merge"
        }

        val nextPriority = (mergeChildRepository.maxPriority(mergeParent.id) ?: -1L) + 1
        mergeChildRepository.addChild(mergeParent.id, anime.id, nextPriority, MERGE_DEFAULT_SEASON_NUMBER)
        // AM (MERGE_SETTINGS) -->
        // The new source's tags join the merged entry's.
        syncMergedEntryInfo.await(mergeParent.id)
        // <-- AM (MERGE_SETTINGS)
    }
}
// <-- AM (MERGED_SOURCES)
