// AM (LOCAL_SOURCE_DOWNLOAD) -->
package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlin.random.Random

/**
 * Where a download goes when it is being saved into the local source rather
 * than owned by the library entry it came from: `<local source
 * root>/<entryName>/<seasonName>/<file>`.
 *
 * A [Download] carrying none of these is an ordinary library download, and
 * nothing about how it is fetched changes either way - only the directory it
 * lands in, and the fact that the file sits in that directory rather than in
 * an episode subdirectory of it.
 *
 * Three levels because that is what the origin of a saved video has: the app
 * or extension it came through, the site that served it, and the file. The
 * local source already reads that shape - an entry directory whose
 * subdirectories are seasons - so everything from one extension collects under
 * one entry with a season per site.
 */
data class LocalSourceTarget(
    val entryName: String,
    val seasonName: String,
) {
    companion object {
        /**
         * Second-level suffixes that are part of the public suffix rather than
         * a name anyone chose, so `example.co.uk` yields `example`.
         *
         * A short list of the common ones rather than the full public suffix
         * list: this names a folder, and being wrong costs an oddly named
         * folder, not a failed download.
         */
        private val SECOND_LEVEL_SUFFIXES = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne")

        /** The entry directory for [source] - the extension's own name. */
        fun entryNameFor(source: AnimeHttpSource): String =
            entryNameFor(source.name)

        /**
         * The entry directory named [app] - the app a video was opened from,
         * where that is known, so a stream handed over by Chrome collects
         * under `Chrome` rather than under whatever source played it.
         */
        fun entryNameFor(app: String): String =
            DiskUtil.buildValidFilename(app)

        /**
         * The season directory for [url] - the site's own domain label, with
         * scheme, `www.`, port, path and public suffix stripped, so
         * `https://internetarchive.com/` becomes `internetarchive`.
         *
         * Taken from the extension's baseUrl rather than a resolved video url:
         * the video is usually served by a CDN host that has nothing to do
         * with where the user thinks they got it.
         *
         * A random id stands in when no site can be read out of [url] at all,
         * so the video still lands somewhere unique.
         */
        fun seasonNameFor(url: String?): String =
            DiskUtil.buildValidFilename(url?.let(::domainLabel) ?: randomId())

        private fun domainLabel(baseUrl: String): String? {
            val host = baseUrl
                .substringAfter("://", baseUrl)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore(':')
                .trim()
                .lowercase()
                .removePrefix("www.")
                .takeIf { it.isNotBlank() }
                ?: return null

            val labels = host.split('.').filter { it.isNotBlank() }
            val label = when {
                labels.isEmpty() -> return null
                labels.size == 1 -> labels[0]
                labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL_SUFFIXES -> labels[labels.size - 3]
                else -> labels[labels.size - 2]
            }
            // An all-digit label is an IP octet, which names nothing.
            return label.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
        }

        private fun randomId(): String = Random.nextInt(0, 0x100000).toString(16).padStart(5, '0')
    }
}
// <-- AM (LOCAL_SOURCE_DOWNLOAD)
