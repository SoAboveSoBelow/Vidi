// AM (PLAYER_MARK_BADGES) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.data.database.models.Episode

/** One episode's marks, as a row draws them. */
data class EpisodeMarks(val bookmarked: Boolean, val fillermarked: Boolean)

/**
 * Marks for the player's episode lists, which have no reactive source for them.
 *
 * The player loads its playlist once per session (see PlayerViewModel's own
 * notes) and marking writes straight to the database, so nothing tells a list
 * its marks changed: a row drew whatever its Episode held when the list was
 * built, and the selection bar's marking left every row looking untouched.
 *
 * This tracks them as state instead, seeded from the episodes themselves, so a
 * row redraws the moment it is marked - from its own button or from the
 * selection bar acting on several at once. The Episode objects are updated too,
 * so reopening the list without a fresh playlist still shows the truth.
 */
@Stable
class EpisodeMarkState(
    private val onBookmarkClicked: (Long?, Boolean) -> Unit,
    private val onFillermarkClicked: (Long?, Boolean) -> Unit,
) {
    private var marks by mutableStateOf(emptyMap<Long, EpisodeMarks>())
    private val episodes = HashMap<Long, Episode>()

    /**
     * Takes in the list being drawn. Episodes already tracked keep what this
     * holds for them - which is newer than the playlist, since the playlist is
     * not refetched - and new ones are seeded from their own values.
     */
    fun sync(list: List<Episode>) {
        val added = list.mapNotNull { episode ->
            val id = episode.id ?: return@mapNotNull null
            episodes[id] = episode
            if (id in marks) return@mapNotNull null
            id to EpisodeMarks(episode.bookmark, episode.fillermark)
        }
        if (added.isNotEmpty()) marks = marks + added
    }

    fun marksOf(episode: Episode): EpisodeMarks {
        return marks[episode.id] ?: EpisodeMarks(episode.bookmark, episode.fillermark)
    }

    fun setBookmark(episodeId: Long?, bookmarked: Boolean) {
        update(episodeId) { it.copy(bookmarked = bookmarked) }
        onBookmarkClicked(episodeId, bookmarked)
    }

    fun setFillermark(episodeId: Long?, fillermarked: Boolean) {
        update(episodeId) { it.copy(fillermarked = fillermarked) }
        onFillermarkClicked(episodeId, fillermarked)
    }

    private fun update(episodeId: Long?, transform: (EpisodeMarks) -> EpisodeMarks) {
        val id = episodeId ?: return
        val episode = episodes[id]
        val current = marks[id] ?: EpisodeMarks(
            bookmarked = episode?.bookmark ?: false,
            fillermarked = episode?.fillermark ?: false,
        )
        val updated = transform(current)
        marks = marks + (id to updated)
        // The playlist's own object, so anything reading it later - a reopened
        // list, the selection bar's "is anything unmarked" checks - agrees.
        episode?.bookmark = updated.bookmarked
        episode?.fillermark = updated.fillermarked
    }
}

@Composable
fun rememberEpisodeMarks(
    episodes: List<Episode>,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
): EpisodeMarkState {
    // Through rememberUpdatedState so the state outliving a recomposition does
    // not pin the first lambdas it was handed.
    val bookmark by rememberUpdatedState(onBookmarkClicked)
    val fillermark by rememberUpdatedState(onFillermarkClicked)
    val state = remember {
        EpisodeMarkState(
            onBookmarkClicked = { id, value -> bookmark(id, value) },
            onFillermarkClicked = { id, value -> fillermark(id, value) },
        )
    }
    // After composition, not during it: sync writes state, and marksOf falls
    // back to the episode's own values for anything it has not seen yet, so the
    // first frame is already correct.
    SideEffect { state.sync(episodes) }
    return state
}
// <-- AM (PLAYER_MARK_BADGES)
