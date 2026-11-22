package eu.kanade.presentation.history

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.history.model.HistoryWithRelations
import java.util.Date
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant

internal class HistoryViewModelStateProvider : PreviewParameterProvider<HistoryPreview> {

    private val multiPage = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = null, dialog = null),
        items = PagingData.from(
            listOf(HistoryUiModelExamples.headerToday)
                .asSequence()
                .plus(HistoryUiModelExamples.items().take(3))
                .plus(HistoryUiModelExamples.header { it.minus(1.days) })
                .plus(HistoryUiModelExamples.items().take(1))
                .plus(HistoryUiModelExamples.header { it.minus(2.days) })
                .plus(HistoryUiModelExamples.items().take(7))
                .toList(),
        ),
    )

    private val shortRecent = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = null, dialog = null),
        items = PagingData.from(
            listOf(
                HistoryUiModelExamples.headerToday,
                HistoryUiModelExamples.items().first(),
            ),
        ),
    )

    private val shortFuture = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = null, dialog = null),
        items = PagingData.from(
            listOf(
                HistoryUiModelExamples.headerTomorrow,
                HistoryUiModelExamples.items().first(),
            ),
        ),
    )

    private val empty = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = null, dialog = null),
        items = PagingData.from(emptyList()),
    )

    private val loadingWithSearchQuery = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = "Example Search Query"),
        items = PagingData.empty(LOADING),
    )

    private val loading = HistoryPreview(
        state = HistoryViewModel.State(searchQuery = null, dialog = null),
        items = PagingData.empty(LOADING),
    )

    private companion object {
        // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): a refresh that has not
        // finished, which is what the nullable list used to stand for.
        val LOADING = LoadStates(
            refresh = LoadState.Loading,
            prepend = LoadState.NotLoading(endOfPaginationReached = false),
            append = LoadState.NotLoading(endOfPaginationReached = false),
        )
    }

    override val values: Sequence<HistoryPreview> = sequenceOf(
        multiPage,
        shortRecent,
        shortFuture,
        empty,
        loadingWithSearchQuery,
        loading,
    )

    private object HistoryUiModelExamples {
        val headerToday = header()
        val headerTomorrow =
            HistoryUiModel.Header(Clock.System.now().plus(1.days).toLocalDateTime(TimeZone.currentSystemDefault()).date)

        fun header(instantBuilder: (Instant) -> Instant = { it }) =
            HistoryUiModel.Header(
                instantBuilder(Clock.System.now()).toLocalDateTime(TimeZone.currentSystemDefault()).date,
            )

        fun items() = sequence {
            var count = 1
            while (true) {
                // AM (CUSTOM_INFORMATION) -->
                yield(randItem { it.copy(ogTitle = "Example Title $count") })
                // <-- AM (CUSTOM_INFORMATION)
                count += 1
            }
        }

        fun randItem(historyBuilder: (HistoryWithRelations) -> HistoryWithRelations = { it }) =
            HistoryUiModel.Item(
                historyBuilder(
                    HistoryWithRelations(
                        id = Random.nextLong(),
                        episodeId = Random.nextLong(),
                        animeId = Random.nextLong(),
                        // AM (CUSTOM_INFORMATION) -->
                        ogTitle = "Test Title",
                        // <-- AM (CUSTOM_INFORMATION)
                        episodeNumber = Random.nextDouble(),
                        seenAt = Date.from(Clock.System.now().toJavaInstant()),
                        coverData = AnimeCover(
                            animeId = Random.nextLong(),
                            sourceId = Random.nextLong(),
                            isAnimeFavorite = Random.nextBoolean(),
                            url = "https://example.com/cover.png",
                            lastModified = Random.nextLong(),
                        ),
                        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
                        episodeName = "Test Episode",
                        scanlator = null,
                        episodeUrl = "/episode/1",
                    ),
                ),
            )
    }
}
