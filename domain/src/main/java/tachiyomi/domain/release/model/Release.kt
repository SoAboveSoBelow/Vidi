package tachiyomi.domain.release.model

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    // AM (WHATS_NEW) -->
    val preRelease: Boolean = false,
    val draft: Boolean = false,
    // <-- AM (WHATS_NEW)
)
