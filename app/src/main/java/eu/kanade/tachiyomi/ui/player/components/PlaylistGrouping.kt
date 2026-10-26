// AM (QUEUE_GROUPED_PLAYLIST) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.runtime.Immutable
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.tachiyomi.data.database.models.Episode

/**
 * The player playlist as rows: queued sources, their seasons, and the episodes
 * inside whichever of those are open.
 *
 * Flattening the tree into a list is what lets the dialog keep rendering
 * through one LazyColumn, so a long queue still composes only what is on
 * screen - a nested column per source would compose every expanded source's
 * episodes whether or not any of them are visible.
 *
 * Grouping is by queue BLOCK rather than by the episode's owning entry, which
 * is the distinction the queue itself draws: a merged entry is one source
 * however many children its episodes come from, and the same entry queued
 * twice is two runs rather than one group that lies about the play order. See
 * queue_blocks.sq.
 */
@Immutable
sealed interface PlaylistRow {

    /** The row's own identity for LazyColumn, stable across expansion changes. */
    val key: String

    /**
     * One queued source.
     *
     * [playEpisodeId] is what tapping the row plays: where the user left this
     * source if they have been in it, else the first episode of the run -
     * which is the first one they queued rather than the entry's own first.
     * Coming back to a source you switched away from and being put back at
     * its beginning loses your place for no reason; the queue remembers it
     * (see QueueBlock.lastPlayedEpisodeId) and this is one of the two things
     * that memory is for.
     *
     * [expandable] is false for a run of one, where there is nothing a chevron
     * could reveal that the row does not already say.
     */
    @Immutable
    data class Source(
        val blockId: Long,
        /** Identifies this consecutive run; one block may appear several times. */
        val runId: Int,
        /** The run's full play order, including children hidden while collapsed. */
        val episodeIds: List<Long>,
        val title: String,
        val episodeCount: Int,
        val playEpisodeId: Long?,
        val expandable: Boolean,
        val expanded: Boolean,
        val containsCurrent: Boolean,
        /** The episode itself for a run of one, rendered by the shared episode row. */
        val singleEpisode: Episode?,
    ) : PlaylistRow {
        override val key: String get() = "source-$runId-$blockId"
    }

    /** One season of a source, where that source has more than one. */
    @Immutable
    data class Season(
        val blockId: Long,
        /** The source run plus season run keeps repeated headers distinct. */
        val runId: String,
        val episodeIds: List<Long>,
        val seasonNumber: Long,
        val label: String,
        val episodeCount: Int,
        val playEpisodeId: Long?,
        val expanded: Boolean,
        val containsCurrent: Boolean,
    ) : PlaylistRow {
        override val key: String get() = "season-$runId-$blockId-$seasonNumber"
    }

    /** An episode, at [depth] 0 when the list is ungrouped. */
    @Immutable
    data class EpisodeItem(
        val episode: Episode,
        val depth: Int,
    ) : PlaylistRow {
        override val key: String get() = "episode-${episode.id}"
    }
}

/**
 * Where tapping a group starts: the remembered episode when it is one of
 * [items], else the first of them.
 */
private fun playTarget(items: List<Episode>, lastPlayed: Long?): Long? {
    val remembered = items.firstOrNull { it.id != null && it.id == lastPlayed }
    return remembered?.id ?: items.firstOrNull()?.id
}

/** A source's season layer, or [NONE] for a source that has no seasons. */
@Immutable
data class PlaylistSeasons(
    val seasonByEpisodeId: Map<Long, Long>,
    val seasons: List<EntrySeason>,
) {
    companion object {
        val NONE = PlaylistSeasons(emptyMap(), emptyList())
    }

    /**
     * Whether these seasons actually divide [episodes] into more than one
     * group.
     *
     * Asked of the episodes in hand rather than of the entry's season count:
     * an entry with four seasons that has only one of them queued is, in this
     * list, one season - and a season row wrapping every episode of a source
     * is a layer that separates nothing while costing a tap to get past.
     */
    fun dividesMoreThanOne(episodes: List<Episode>): Boolean {
        if (seasons.size <= 1) return false
        return episodes.asSequence()
            .mapNotNull { it.id?.let(seasonByEpisodeId::get) }
            .distinct()
            .take(2)
            .count() > 1
    }
}

/** Which source and season rows are open. */
@Immutable
data class PlaylistExpansion(
    val sources: Set<Long> = emptySet(),
    val seasons: Set<Pair<Long, Long>> = emptySet(),
)

/**
 * The consecutive runs of [episodes] that belong to the same queued source.
 *
 * Consecutive rather than grouped by key: episode dragging may interleave a
 * source with others, and gathering by block would silently merge separate
 * runs and lie about global play order. Stable block membership still supplies
 * the source label for every run.
 */
fun playlistBlocks(
    episodes: List<Episode>,
    blockOf: (Episode) -> Long?,
): List<Pair<Long?, List<Episode>>> {
    val runs = mutableListOf<Pair<Long?, MutableList<Episode>>>()
    episodes.forEach { episode ->
        val blockId = blockOf(episode)
        val current = runs.lastOrNull()
        if (current != null && current.first == blockId) {
            current.second += episode
        } else {
            runs += blockId to mutableListOf(episode)
        }
    }
    return runs.map { (blockId, items) -> blockId to items.toList() }
}

/**
 * Builds the rows to draw.
 *
 * [seasonsOf] returns null while a source's seasons have not been resolved
 * yet, which draws as a source whose episodes sit directly under it; the
 * answer landing later simply inserts the season layer. See
 * PlayerViewModel.loadBlockSeasons for why that resolve is deferred.
 *
 * A queue holding ONE source is rendered without the source layer at all:
 * wrapping an entry's own episodes in a single collapsible row hides the list
 * behind a tap and groups nothing, since there is nothing to group it against.
 * Its seasons still group, because those do divide it.
 */
fun buildPlaylistRows(
    episodes: List<Episode>,
    blockOf: (Episode) -> Long?,
    titleOf: (Long, List<Episode>) -> String,
    seasonsOf: (Long) -> PlaylistSeasons?,
    seasonLabelOf: (Long, List<EntrySeason>) -> String,
    expansion: PlaylistExpansion,
    currentEpisodeId: Long?,
    /** Where the user left each source, by block id - see PlaylistRow.Source. */
    lastPlayedByBlockId: Map<Long, Long>,
): List<PlaylistRow> {
    val blocks = playlistBlocks(episodes, blockOf)
    val rows = mutableListOf<PlaylistRow>()

    // No blocks at all (an external session, or a playlist read before the
    // queue was), or exactly one source: a plain list of episodes.
    if (blocks.size <= 1) {
        val single = blocks.singleOrNull()
        val blockId = single?.first
        val seasons = blockId?.let(seasonsOf)
        if (single != null && blockId != null && seasons != null &&
            seasons.dividesMoreThanOne(single.second)
        ) {
            rows += seasonRows(
                blockId = blockId,
                sourceRunId = 0,
                episodes = single.second,
                seasons = seasons,
                seasonLabelOf = seasonLabelOf,
                expansion = expansion,
                currentEpisodeId = currentEpisodeId,
                lastPlayed = lastPlayedByBlockId[blockId],
                depth = 0,
            )
            return rows
        }
        return episodes.map { PlaylistRow.EpisodeItem(it, depth = 0) }
    }

    blocks.forEachIndexed { sourceRunId, (blockId, items) ->
        if (blockId == null) {
            rows += items.map { PlaylistRow.EpisodeItem(it, depth = 0) }
            return@forEachIndexed
        }
        val containsCurrent = currentEpisodeId != null && items.any { it.id == currentEpisodeId }
        val expandable = items.size > 1
        val expanded = expandable && blockId in expansion.sources
        rows += PlaylistRow.Source(
            blockId = blockId,
            runId = sourceRunId,
            episodeIds = items.mapNotNull { it.id },
            title = titleOf(blockId, items),
            episodeCount = items.size,
            playEpisodeId = playTarget(items, lastPlayedByBlockId[blockId]),
            expandable = expandable,
            expanded = expanded,
            containsCurrent = containsCurrent,
            singleEpisode = items.singleOrNull(),
        )
        if (!expanded) return@forEachIndexed

        val seasons = seasonsOf(blockId)
        if (seasons != null && seasons.dividesMoreThanOne(items)) {
            rows += seasonRows(
                blockId = blockId,
                sourceRunId = sourceRunId,
                episodes = items,
                seasons = seasons,
                seasonLabelOf = seasonLabelOf,
                expansion = expansion,
                currentEpisodeId = currentEpisodeId,
                lastPlayed = lastPlayedByBlockId[blockId],
                depth = 1,
            )
        } else {
            rows += items.map { PlaylistRow.EpisodeItem(it, depth = 1) }
        }
    }
    return rows
}

/**
 * The season rows of one source, in the order its episodes already run.
 *
 * Seasons are taken in first-appearance order rather than in the entry's own
 * season order: the queue's order is the play order, and a season layer that
 * listed them in a different one would say this plays before that when it does
 * not.
 */
private fun seasonRows(
    blockId: Long,
    sourceRunId: Int,
    episodes: List<Episode>,
    seasons: PlaylistSeasons,
    seasonLabelOf: (Long, List<EntrySeason>) -> String,
    expansion: PlaylistExpansion,
    currentEpisodeId: Long?,
    lastPlayed: Long?,
    depth: Int,
): List<PlaylistRow> {
    val rows = mutableListOf<PlaylistRow>()
    val runs = mutableListOf<Pair<Long?, MutableList<Episode>>>()
    episodes.forEach { episode ->
        val season = episode.id?.let { seasons.seasonByEpisodeId[it] }
        val current = runs.lastOrNull()
        if (current != null && current.first == season) {
            current.second += episode
        } else {
            runs += season to mutableListOf(episode)
        }
    }

    runs.forEachIndexed { seasonRunId, (seasonNumber, items) ->
        if (seasonNumber == null) {
            // An episode the resolve has no season for - a queued episode of
            // another entry caught in this block, or one dropped since. It
            // belongs to the source, not to a season, so it lists directly.
            rows += items.map { PlaylistRow.EpisodeItem(it, depth = depth + 1) }
            return@forEachIndexed
        }
        val containsCurrent = currentEpisodeId != null && items.any { it.id == currentEpisodeId }
        val expanded = (blockId to seasonNumber) in expansion.seasons
        rows += PlaylistRow.Season(
            blockId = blockId,
            runId = "$sourceRunId-$seasonRunId",
            episodeIds = items.mapNotNull { it.id },
            seasonNumber = seasonNumber,
            label = seasonLabelOf(seasonNumber, seasons.seasons),
            episodeCount = items.size,
            playEpisodeId = playTarget(items, lastPlayed),
            expanded = expanded,
            containsCurrent = containsCurrent,
        )
        if (expanded) {
            rows += items.map { PlaylistRow.EpisodeItem(it, depth = depth + 1) }
        }
    }
    return rows
}
// <-- AM (QUEUE_GROUPED_PLAYLIST)
