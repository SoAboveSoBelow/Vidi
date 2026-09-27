package mihon.feature.library

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.util.formatEpisodeNumber
import mihon.domain.library.model.search.AndNode
import mihon.domain.library.model.search.AnimeField
import mihon.domain.library.model.search.ComparisonQueryNode
import mihon.domain.library.model.search.EmptyQueryNode
import mihon.domain.library.model.search.FieldQueryNode
import mihon.domain.library.model.search.GeneralQueryNode
import mihon.domain.library.model.search.NotNode
import mihon.domain.library.model.search.OrNode
import mihon.domain.library.model.search.QueryNode
import tachiyomi.domain.anime.model.Anime
import tachiyomi.source.local.LocalSource

// AM (EPISODE_TAG_SEARCH) -->
/**
 * What an episode looks like to the search grammar.
 *
 * [anime] is the entry the episode actually belongs to, which on a merged
 * entry is the child source rather than the merge parent - that is the whole
 * point of searching by tag inside a merged entry, since each child brings its
 * own tags.
 *
 * [tags] is separate from `anime.genre` on purpose. Today it is exactly the
 * owning entry's genres, but it is the seam for per-episode tags later: adding
 * those means filling this list differently at the two places that build it,
 * with nothing in the grammar or the matcher below needing to change.
 */
@Immutable
data class EpisodeSearchFields(
    val displayName: String,
    val episodeNumber: Double,
    val scanlator: String?,
    val anime: Anime,
    val sourceName: String,
    val tags: List<String>,
)

/**
 * The episode-list counterpart of [QueryNode.matches] for a LibraryItem - same
 * grammar, same field names, matched against an episode and the entry it came
 * from. So `tag:action`, `-tag:filler`, `source:foo` and the boolean operators
 * behave inside a playlist exactly as they do in the library search bar.
 */
fun QueryNode.matches(fields: EpisodeSearchFields): Boolean {
    return when (this) {
        is AndNode -> children.all { it.matches(fields) }
        is OrNode -> children.any { it.matches(fields) }
        is NotNode -> !child.matches(fields)
        is EmptyQueryNode -> true
        is GeneralQueryNode -> matches(fields)
        is FieldQueryNode -> matches(fields)
        // Library-level counts (unseen:, seen:, total:) and entry metadata
        // comparisons have no per-episode meaning. Matching nothing is the
        // honest answer; silently matching everything would make a mistyped
        // query look like it worked.
        is ComparisonQueryNode -> negated
    }
}

private fun GeneralQueryNode.matches(fields: EpisodeSearchFields): Boolean {
    val anime = fields.anime

    // An unfielded term hits the episode first, then the same entry fields the
    // library's own general search covers - so typing a tag with no prefix
    // works here for the same reason it works there.
    val episodeMatch = fields.displayName.contains(value, ignoreCase = true) ||
        formatEpisodeNumber(fields.episodeNumber).contains(value, ignoreCase = true) ||
        fields.scanlator?.contains(value, ignoreCase = true) == true

    val animeMatch = AnimeField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            AnimeField.TITLE -> anime.title.contains(value, ignoreCase = true)
            AnimeField.AUTHOR -> anime.author?.contains(value, ignoreCase = true) ?: false
            AnimeField.ARTIST -> anime.artist?.contains(value, ignoreCase = true) ?: false
            AnimeField.DESCRIPTION -> anime.description?.contains(value, ignoreCase = true) ?: false
            AnimeField.GENRE -> fields.tags.any { it.contains(value, ignoreCase = true) }
            AnimeField.SOURCE -> {
                fields.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && anime.source == LocalSource.ID)
            }
            AnimeField.NOTES -> anime.notes.contains(value, ignoreCase = true)

            // field-only queries; unreachable; added here to make `when` exhaustive
            AnimeField.LANGUAGE, AnimeField.SOURCE_ID -> error("How did we get here?")
        }
    }

    val match = episodeMatch || animeMatch
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(fields: EpisodeSearchFields): Boolean {
    val anime = fields.anime

    val match = when (field) {
        AnimeField.GENRE -> {
            if (value.isEmpty()) fields.tags.isEmpty() else fields.tags.any { it.contains(value, ignoreCase = true) }
        }

        AnimeField.SOURCE -> {
            if (value.isEmpty()) {
                fields.sourceName.isEmpty()
            } else {
                fields.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && anime.source == LocalSource.ID)
            }
        }

        AnimeField.SOURCE_ID -> value.toLongOrNull()?.let { it == anime.source } ?: false

        else -> {
            val text = when (field) {
                AnimeField.TITLE -> anime.title
                AnimeField.AUTHOR -> anime.author
                AnimeField.ARTIST -> anime.artist
                AnimeField.DESCRIPTION -> anime.description
                AnimeField.NOTES -> anime.notes
                // The episode list has no per-source language to match on.
                AnimeField.LANGUAGE -> null

                // unreachable; added here to make `when` exhaustive
                AnimeField.GENRE, AnimeField.SOURCE, AnimeField.SOURCE_ID -> error("How did we get here?")
            }

            if (value.isEmpty()) text.isNullOrEmpty() else text?.contains(value, ignoreCase = true) ?: false
        }
    }

    return if (negated) !match else match
}
// <-- AM (EPISODE_TAG_SEARCH)
