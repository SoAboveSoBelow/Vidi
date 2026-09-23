// AM (MERGE_SETTINGS) -->
package aniyomi.domain.merge.model

/** How a merge handles the same episode offered by more than one source. */
enum class DedupeMode(val value: Long) {
    /** Every source's copy is listed. */
    OFF(0),

    /**
     * Within each season, an episode number offered by several sources is
     * listed once, from the highest-priority source that has it. Episodes
     * with no recognised number are never treated as duplicates.
     */
    PRIORITY(1),

    /**
     * Within each season, only the source with the most episodes is listed.
     * Like Komikku, this picks a whole source rather than matching numbers -
     * per season, so a multi-season merge isn't reduced to one source overall.
     */
    MOST_EPISODES(2),

    /** Within each season, only the source reaching the highest episode number is listed. */
    HIGHEST_EPISODE(3),
    ;

    companion object {
        fun from(value: Long): DedupeMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

/** [infoAnimeId] null means the highest-priority source supplies the details. */
data class MergeSettings(
    val dedupeMode: DedupeMode = DedupeMode.OFF,
    val infoAnimeId: Long? = null,
)
// <-- AM (MERGE_SETTINGS)
