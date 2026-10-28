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
        /**
         * How many episodes this RUN holds - what sits under this header.
         */
        val episodeCount: Int,
        /**
         * How many are queued for the SOURCE, across every run of it.
         *
         * Both, because neither alone is the truth once a source is split:
         * the run's count makes each header understate the source, and the
         * source's total makes every header overstate what is under it. With
         * the two the row can say "8 of 24", and say plain "24" when they are
         * the same number and there is nothing to distinguish.
         */
        val sourceEpisodeCount: Int,
        val playEpisodeId: Long?,
        val expandable: Boolean,
        val expanded: Boolean,
        val containsCurrent: Boolean,
        /** The episode itself for a run of one, rendered by the shared episode row. */
        val singleEpisode: Episode?,
    ) : PlaylistRow {
        override val key: String get() = sourceKey(runId, blockId)
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
        /** What sits under this header. */
        val episodeCount: Int,
        /** This season's queued episodes across the whole source - see Source. */
        val sourceEpisodeCount: Int,
        val playEpisodeId: Long?,
        val expanded: Boolean,
        val containsCurrent: Boolean,
    ) : PlaylistRow {
        override val key: String get() = seasonKey(runId, blockId, seasonNumber)
    }

    /** An episode, at [depth] 0 when the list is ungrouped. */
    @Immutable
    data class EpisodeItem(
        val episode: Episode,
        val depth: Int,
    ) : PlaylistRow {
        override val key: String get() = "episode-${episode.id}"
    }

    // AM (QUEUE_EXPAND_PER_RUN) -->
    /**
     * The row keys, built here rather than at each use so the expansion set
     * and the rows are guaranteed to be talking about the same rows.
     */
    companion object {
        fun sourceKey(runId: Int, blockId: Long): String = "source-$runId-$blockId"

        fun seasonKey(runId: String, blockId: Long, seasonNumber: Long): String =
            "season-$runId-$blockId-$seasonNumber"
    }
    // <-- AM (QUEUE_EXPAND_PER_RUN)
}

// AM (PLAYLIST_ADD_TO_LIBRARY) -->
/**
 * One group row the user selected as a group, kept beside the episode-id
 * selection - see the episode list dialog for why the distinction is recorded
 * rather than worked out afterwards.
 *
 * [episodeIds] is what the row held when it was selected, which is what says
 * whether the record still holds: all of them still selected means the row is
 * still a selected group, and anything less means the user has gone back to
 * picking episodes inside it.
 */
@Immutable
data class PlaylistGroupSelection(
    val blockId: Long,
    val episodeIds: List<Long>,
)
// <-- AM (PLAYLIST_ADD_TO_LIBRARY)

// AM (QUEUE_HOVER_EXPAND) -->
/**
 * Whether [row] sits inside this one - this row being a group and [row] one of
 * the rows it reveals.
 *
 * Membership by episode rather than by block or season number, so it holds for
 * one RUN of a source rather than every run of it: the rows a group reveals
 * are exactly the rows whose episodes are its own.
 */
fun PlaylistRow?.contains(row: PlaylistRow?): Boolean {
    if (this == null || row == null || this === row) return false
    val owned = when (this) {
        is PlaylistRow.Source -> episodeIds.toSet()
        is PlaylistRow.Season -> episodeIds.toSet()
        is PlaylistRow.EpisodeItem -> return false
    }
    if (owned.isEmpty()) return false
    return when (row) {
        is PlaylistRow.EpisodeItem -> row.episode.id in owned
        is PlaylistRow.Season -> row.episodeIds.isNotEmpty() && row.episodeIds.all(owned::contains)
        is PlaylistRow.Source -> false
    }
}
// <-- AM (QUEUE_HOVER_EXPAND)

// AM (QUEUE_EXPAND_PER_RUN) -->
/**
 * The source row key for the run holding [episodeId], for opening the list on
 * the run that is playing rather than on every run of its source.
 *
 * Repeats [buildPlaylistRows]' own "is there a source layer at all" guard so
 * the two agree about whether source rows exist and what their run ids are;
 * the runs themselves come from the same [playlistBlocks] call it uses.
 */
fun playingSourceKey(
    episodes: List<Episode>,
    blockOf: (Episode) -> Long?,
    episodeId: Long?,
): String? {
    if (episodeId == null) return null
    val blocks = playlistBlocks(episodes, blockOf)
    if (blocks.size <= 1 || blocks.any { it.first == null }) return null
    blocks.forEachIndexed { runId, (blockId, items) ->
        if (blockId != null && items.any { it.id != null && it.id == episodeId }) {
            return PlaylistRow.sourceKey(runId, blockId)
        }
    }
    return null
}
// <-- AM (QUEUE_EXPAND_PER_RUN)

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

/**
 * Which source and season rows are open, by row key.
 *
 * AM (QUEUE_EXPAND_PER_RUN): by KEY rather than by block or by
 * block-and-season. A source can appear as several runs once episodes are
 * interleaved, and those runs are separate rows with separate contents - so
 * keying on the block opened every run of it at once, from one chevron. The
 * row key is the identity the list already uses for each row (it carries the
 * run), so an expansion now describes exactly the row that was tapped.
 */
@Immutable
data class PlaylistExpansion(
    val sources: Set<String> = emptySet(),
    val seasons: Set<String> = emptySet(),
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
    /**
     * The source an episode belongs to, across runs and blocks - the entry it
     * was queued as. What the headers' counts are totalled by; see
     * PlaylistRow.Source.episodeCount.
     */
    sourceIdOf: (Episode) -> Long?,
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
    // AM (QUEUE_SOURCE_TOTALS): one pass, then every header reads its total
    // from it rather than re-scanning the list per row.
    val episodesBySource = episodes.groupBy(sourceIdOf)

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
                // AM (QUEUE_SOURCE_TOTALS): one source in the list, so its
                // runs and its totals are the same thing.
                seasonCountOf = { seasonNumber ->
                    single.second.count { episode ->
                        episode.id?.let { seasons.seasonByEpisodeId[it] } == seasonNumber
                    }
                },
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
        // AM (QUEUE_EXPAND_PER_RUN): the same key the row reports, built here
        // so the row's expanded flag and its identity cannot disagree.
        val sourceKey = PlaylistRow.sourceKey(sourceRunId, blockId)
        val expanded = expandable && sourceKey in expansion.sources
        // AM (QUEUE_SOURCE_TOTALS)
        val sourceEpisodes = episodesBySource[items.firstNotNullOfOrNull(sourceIdOf)] ?: items
        rows += PlaylistRow.Source(
            blockId = blockId,
            runId = sourceRunId,
            episodeIds = items.mapNotNull { it.id },
            title = titleOf(blockId, items),
            // AM (QUEUE_SOURCE_TOTALS)
            episodeCount = items.size,
            sourceEpisodeCount = sourceEpisodes.size,
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
                // AM (QUEUE_SOURCE_TOTALS): a season's total across the whole
                // source, for the same reason the source's own count is.
                seasonCountOf = { seasonNumber ->
                    sourceEpisodes.count { episode ->
                        episode.id?.let { seasons.seasonByEpisodeId[it] } == seasonNumber
                    }
                },
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
    // AM (QUEUE_SOURCE_TOTALS)
    seasonCountOf: (Long) -> Int,
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
        // AM (QUEUE_EXPAND_PER_RUN)
        val seasonKey = PlaylistRow.seasonKey("$sourceRunId-$seasonRunId", blockId, seasonNumber)
        val expanded = seasonKey in expansion.seasons
        rows += PlaylistRow.Season(
            blockId = blockId,
            runId = "$sourceRunId-$seasonRunId",
            episodeIds = items.mapNotNull { it.id },
            seasonNumber = seasonNumber,
            label = seasonLabelOf(seasonNumber, seasons.seasons),
            // AM (QUEUE_SOURCE_TOTALS)
            episodeCount = items.size,
            sourceEpisodeCount = seasonCountOf(seasonNumber),
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
