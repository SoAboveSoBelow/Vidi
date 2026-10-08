// AM (PLANNED_DOWNLOADS) -->
package animiru.domain.download.model

/**
 * Why an episode is in the planned list (see planned_downloads.sq).
 *
 * Informational: it tells the user how a row got there and nothing else. Removal,
 * completion and re-adding behave identically whatever the origin - the row's
 * existence is the whole state, so clearing it clears the history with it.
 *
 * Stored as an ordinal, so the declaration order is persisted - append only.
 */
enum class PlannedDownloadOrigin {
    /** A backup said this episode was downloaded on the device it came from. */
    Restored,

    /** Cancelled out of the download queue, which holds rather than discards. */
    Held,

    /** Chosen directly, without being queued yet. */
    Planned,

    ;

    companion object {
        /** Decodes a stored ordinal, treating anything unrecognised as [Restored]. */
        fun fromOrdinal(value: Long): PlannedDownloadOrigin = entries.getOrElse(value.toInt()) { Restored }
    }
}

/**
 * One planned download, with the episode and entry details around it.
 *
 * [animeTitle] is the source's own title, not a custom one: it is what the download
 * folder is named after, so it is also what decides whether the episode counts as
 * already present on disk.
 *
 * [episodePreviewUrl] falls back to [animeThumbnailUrl] for display - for a
 * single-episode entry the cover *is* that episode's thumbnail, the same precedence
 * the episode list applies (see AM (SINGLE_EPISODE_THUMBNAIL)).
 */
data class PlannedDownloadEntry(
    val animeId: Long,
    val episodeId: Long,
    val origin: PlannedDownloadOrigin,
    val createdAt: Long,
    val animeTitle: String,
    val sourceId: Long,
    val episodeName: String,
    val episodeScanlator: String?,
    val episodeUrl: String,
    val episodeNumber: Double,
    val episodePreviewUrl: String?,
    val animeThumbnailUrl: String?,
) {
    /** What to show for this row - the episode's frame if there is one, else the cover. */
    val displayImageUrl: String? get() = episodePreviewUrl ?: animeThumbnailUrl
}
// <-- AM (PLANNED_DOWNLOADS)
