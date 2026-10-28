// AM (PLAYLIST_MARK_TRISTATE) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA

/**
 * What a mark currently is across a selection, and what the next tap makes it.
 *
 * The three states are the three a selection can actually be in - none of it
 * marked, all of it marked, or some of it - and they map onto the tri-state
 * the category dialog already uses for the same question about a set:
 * [CheckboxState.TriState.None] is "leave them as they are",
 * [CheckboxState.TriState.Include] marks all, [CheckboxState.TriState.Exclude]
 * unmarks all. The cycle is that type's own, so a mark button and a category
 * row advance in the same order.
 *
 * Unlike the category dialog this needs no dialog to carry it: a mark has
 * nothing to choose but its state, so the tap applies it. Returning to the
 * mixed state is therefore an undo, which is why the caller snapshots what the
 * marks were when the cycle began - see MarkCycle.
 */
enum class MarkTriState {
    /** Mixed, or restored to however the selection started. */
    PARTIAL,
    ALL,
    NONE,
    ;

    // AM (PLAYLIST_MARK_TRISTATE): no next() here. What follows NONE depends
    // on whether there is a mixed state to go back TO, which is the cycle's
    // business rather than the state's - see MarkCycle.advance.
}

/**
 * The three glyphs one mark cycles through.
 *
 * A distinct icon per state rather than one icon re-tinted: tint alone asks
 * the user to compare a colour against the other buttons to work out which
 * state they are looking at, and says nothing at all to a screen reader or in
 * a high-contrast theme. Each mark has an add and a remove glyph already, so
 * [all] and [none] are those, and [partial] is the family's plain glyph -
 * which reads as "this mark, neither added nor removed".
 */
data class MarkTriStateIcons(
    val all: ImageVector,
    val partial: ImageVector,
    val none: ImageVector,
) {
    fun forState(state: MarkTriState): ImageVector = when (state) {
        MarkTriState.ALL -> all
        MarkTriState.PARTIAL -> partial
        MarkTriState.NONE -> none
    }
}

/**
 * One selection-bar mark action, as a single button that cycles.
 *
 * Replaces the add/remove pair each mark used to need. Two buttons per mark
 * filled the bar with six for three marks, and each was offered only when it
 * would change something - so the bar's contents shifted under the user as
 * their selection changed. One button per mark holds its place and says which
 * of the three states the selection is in.
 */
@Composable
fun MarkTriStateButton(
    state: MarkTriState,
    icons: MarkTriStateIcons,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // The state goes through stateDescription rather than into the label,
    // which is what that semantic is for: the description stays the mark's
    // name and the state is announced as a state, the way a checkbox's is.
    val stateLabel = stringResource(
        when (state) {
            MarkTriState.ALL -> AMMR.strings.am_mark_state_all
            MarkTriState.PARTIAL -> AMMR.strings.am_mark_state_partial
            MarkTriState.NONE -> AMMR.strings.am_mark_state_none
        },
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { stateDescription = stateLabel },
    ) {
        Icon(
            imageVector = icons.forState(state),
            contentDescription = contentDescription,
            // Colour still carries "marked", as it does in the rows, but it is
            // no longer the only thing distinguishing the three.
            tint = when (state) {
                MarkTriState.ALL -> MaterialTheme.colorScheme.primary
                MarkTriState.PARTIAL -> MaterialTheme.colorScheme.onSurface
                MarkTriState.NONE -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .size(24.dp)
                .then(if (state == MarkTriState.NONE) Modifier.alpha(DISABLED_ALPHA) else Modifier),
        )
    }
}

/**
 * One mark's cycle over the open selection.
 *
 * Holds what the marks were when the cycle started, because the third state is
 * "leave them as they were" and with no dialog to cancel, that is only
 * expressible by putting them back. Snapshotted per mark at the first tap
 * rather than when the selection is made: a selection grows and shrinks, and
 * the snapshot has to describe the rows the cycle is actually acting on.
 *
 * [observed] is what the selection reads as before any tap - all, none, or
 * mixed - which is also where the cycle restarts after a restore.
 */
class MarkCycle<T>(
    private val idsOf: () -> List<T>,
    private val markedOf: (T) -> Boolean,
    private val setMark: (T, Boolean) -> Unit,
) {
    private var snapshot: Map<T, Boolean>? = null
    private var startedMixed = false

    /**
     * Read from the rows every time rather than cached.
     *
     * Each step below leaves the rows in exactly the state it moved to, so the
     * rows ARE the state - and a cached copy would be a plain field that
     * composition does not watch, so the button could show one thing while the
     * rows said another.
     */
    val observed: MarkTriState
        get() {
            val ids = idsOf()
            return when {
                ids.isEmpty() -> MarkTriState.PARTIAL
                ids.all(markedOf) -> MarkTriState.ALL
                ids.none(markedOf) -> MarkTriState.NONE
                else -> MarkTriState.PARTIAL
            }
        }

    /**
     * Applies the next state.
     *
     * Mixed goes to all, all goes to none, and what follows none depends on
     * where the cycle began. From a mixed selection there is something to go
     * back to, so none restores it - three states, as a tri-state checkbox
     * has. From a selection that was uniform to begin with there is no mixed
     * state to restore, so none goes back to all and the button is a plain
     * toggle.
     *
     * Getting that wrong is what made the button look broken: a selection with
     * nothing marked reads as none, and "restore" then put back the same
     * nothing, so the tap did visibly nothing at all.
     */
    fun advance() {
        val ids = idsOf()
        if (ids.isEmpty()) return
        val current = observed
        if (snapshot == null) {
            snapshot = ids.associateWith(markedOf)
            startedMixed = current == MarkTriState.PARTIAL
        }
        when (current) {
            MarkTriState.PARTIAL -> ids.forEach { setMark(it, true) }
            MarkTriState.ALL -> ids.forEach { setMark(it, false) }
            MarkTriState.NONE -> {
                if (startedMixed) {
                    val original = snapshot.orEmpty()
                    ids.forEach { id -> original[id]?.let { setMark(id, it) } }
                    // Back where it started, so the next tap begins a fresh
                    // cycle against whatever the rows are then.
                    reset()
                } else {
                    ids.forEach { setMark(it, true) }
                }
            }
        }
    }

    /** Drops the cycle when the selection changes under it. */
    fun reset() {
        snapshot = null
        startedMixed = false
    }
}
// <-- AM (PLAYLIST_MARK_TRISTATE)
