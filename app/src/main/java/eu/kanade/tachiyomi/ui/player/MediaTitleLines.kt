// AM (MEDIA_TITLE_DEDUPE) -->
package eu.kanade.tachiyomi.ui.player

/**
 * Collapses a two-slot media label - a heading plus a subheading - down to a
 * single line whenever the second slot says nothing the first does not say
 * already.
 *
 * Every surface that shows "what is playing" has exactly two text slots and
 * fills them from the same two strings: the entry title and the episode title.
 * Those two are not always distinct - a single-entry video (an external intent,
 * a loose local file, a one-episode entry named after itself) carries the same
 * text in both, and the surface then renders it twice.
 *
 * Fixing that per call site would only cover the cases someone thought of, so
 * the pair itself is normalized here instead: identical titles, titles that
 * differ only in casing, spacing or punctuation, and an episode name that
 * already contains the entry name (or the reverse) all collapse the same way,
 * keeping whichever line carries more.
 *
 * [primary] is whichever string the calling surface leads with, so each keeps
 * its own ordering - the notification leads with the entry title, MediaSession
 * metadata leads with the episode title in its TITLE slot. The returned first
 * line is always the one to display; the second is null when there is nothing
 * left to add, and the caller omits that slot.
 */
fun dedupeMediaTitleLines(primary: String?, secondary: String?): Pair<String, String?> {
    val first = primary?.trim().orEmpty()
    val second = secondary?.trim().orEmpty()
    if (first.isEmpty()) return second to null
    if (second.isEmpty()) return first to null

    val firstKey = comparisonKey(first)
    val secondKey = comparisonKey(second)
    // Nothing comparable survived normalization (a title of pure punctuation),
    // so there is no evidence of redundancy - show both rather than guess.
    if (firstKey.isEmpty() || secondKey.isEmpty()) return first to second

    // Padded so containment is token-aligned: "K" is redundant against
    // "K - Episode 1" but not against "Kaguya-sama".
    val redundant = " $firstKey ".contains(" $secondKey ") ||
        " $secondKey ".contains(" $firstKey ")
    if (!redundant) return first to second

    return if (secondKey.length > firstKey.length) second to null else first to null
}

/**
 * Lowercased, alphanumerics only, every run of anything else folded to a single
 * space - so "Bleach: Episode 1" and "bleach - episode 1" compare equal, and
 * the result can be matched on token boundaries.
 */
private fun comparisonKey(value: String): String {
    val builder = StringBuilder(value.length)
    var pendingSeparator = false
    value.forEach { char ->
        if (char.isLetterOrDigit()) {
            if (pendingSeparator && builder.isNotEmpty()) builder.append(' ')
            pendingSeparator = false
            builder.append(char.lowercaseChar())
        } else {
            pendingSeparator = true
        }
    }
    return builder.toString()
}
// <-- AM (MEDIA_TITLE_DEDUPE)
