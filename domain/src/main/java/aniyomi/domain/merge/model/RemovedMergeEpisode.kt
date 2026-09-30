// AM (MERGE_EPISODE_EXCLUSION) -->
package aniyomi.domain.merge.model

/** One episode removed from a merge, resolved for display in merge settings. */
data class RemovedMergeEpisode(
    val episodeId: Long,
    /** The custom name where the episode has one, else the source's. */
    val episodeName: String,
    /** The child source the episode belongs to, so identical names stay distinguishable. */
    val sourceTitle: String,
    val episodeNumber: Double,
)
// <-- AM (MERGE_EPISODE_EXCLUSION)
