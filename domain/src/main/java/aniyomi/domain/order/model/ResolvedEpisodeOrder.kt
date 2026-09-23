// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.order.model

import aniyomi.domain.season.model.EntrySeason
import tachiyomi.domain.episode.model.Episode

/**
 * An entry's episodes in their final order, plus what each one resolved to.
 * See aniyomi.domain.order.interactor.GetEpisodeOrder.
 *
 * [isPreordered] means callers must use [episodes] as-is rather than applying
 * their own sort - see GetEpisodeOrder.isPreordered for when that holds.
 *
 * [sortKeyByEpisodeId] and [defaultSeasonByEpisodeId] are what edits are
 * computed against: a drop lands between two neighbours' effective keys, and a
 * season is only stored when it differs from the episode's default.
 */
data class ResolvedEpisodeOrder(
    val episodes: List<Episode>,
    val seasonByEpisodeId: Map<Long, Long>,
    val sortKeyByEpisodeId: Map<Long, Double>,
    val defaultSortKeyByEpisodeId: Map<Long, Double>,
    val defaultSeasonByEpisodeId: Map<Long, Long>,
    /**
     * Every season of the entry in display order - named or not, empty or not,
     * always including the default. This is what labels are derived from: an
     * unnamed season is "Season <its 1-based position here>".
     */
    val seasons: List<EntrySeason>,
    /**
     * Merge priority of each episode's source (0 for an ordinary entry). Sources
     * interleave by episode number, so two sources' same-numbered episodes tie
     * on key; the higher-priority one lists first.
     */
    val priorityByEpisodeId: Map<Long, Long>,
    /**
     * What each episode is called on screen - a custom name if one is set, the
     * source's title for a merged entry's single-episode source, else the
     * episode's own name. Display only; episodes.name is untouched, so
     * downloads still resolve by it.
     */
    val displayNameByEpisodeId: Map<Long, String>,
    val isPreordered: Boolean,
) {
    /** Display rank of each season number - what episodes are ordered by, rather than the number itself. */
    val seasonRankByNumber: Map<Long, Int> by lazy { seasons.withIndex().associate { (i, s) -> s.number to i } }

    /**
     * Seasons that actually hold episodes, in display order. A single entry
     * means there is nothing to switch between; empty seasons only show up
     * where you'd move episodes INTO them.
     */
    val seasonNumbers: List<Long> by lazy {
        val used = seasonByEpisodeId.values.toHashSet()
        seasons.map { it.number }.filter { it in used }
    }

    /**
     * The stored order itself: (season, effective key, id) ascending, with no
     * display layer applied. This is what the reorder mode shows and edits.
     * Equal to [episodes] whenever the entry is preordered; for an ordinary
     * entry with no custom order it differs, because that entry's list is
     * still sorted by its display setting.
     */
    val canonicalEpisodes: List<Episode> by lazy {
        episodes.sortedWith(
            compareBy<Episode>(
                { seasonByEpisodeId[it.id]?.let(seasonRankByNumber::get) },
                { sortKeyByEpisodeId[it.id] },
                { priorityByEpisodeId[it.id] },
                { it.id },
            ),
        )
    }

    companion object {
        val EMPTY = ResolvedEpisodeOrder(
            emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(),
            isPreordered = false,
        )
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
