package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.runtime.Immutable
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding

// AM (CONTROL_BINDINGS) -->
/**
 * Whether the thing a slot is bound to exists for what is playing right now.
 *
 * Before the regions were rendered from stored bindings, a control that had
 * nothing to act on was simply not emitted into its row, and the buttons either
 * side of it closed the gap: quality only existed for an online episode, the
 * chapter indicator only while a chapter was known, picture-in-picture only
 * where the device offers it, cast only with casting on. Rendering from a stored
 * list kept those conditions but spent a slot on them anyway, so the row drew a
 * hole and - where the window was already full - held a later stored control off
 * the bar to keep a position nothing could use.
 *
 * This restores the old behavior for every region: an unavailable binding is not
 * a slot, so the next stored control takes its place. Nothing is written back -
 * the stored list keeps its full length and order, exactly as with a window too
 * narrow to show all of it, so the control reappears where it was the moment it
 * has something to do again.
 *
 * The editor is the one place that shows every stored position regardless; see
 * [isRendered].
 */
@Immutable
data class ControlAvailability(
    /** Null while unknown - treated as unavailable, as the old condition did. */
    val isEpisodeOnline: Boolean? = null,
    /** The chapter indicator's own condition: indicator on AND a chapter known. */
    val hasChapterIndicator: Boolean = false,
    val isPipAvailable: Boolean = false,
    /** Cast compiled in AND enabled, which is what the old row asked. */
    val isCastAvailable: Boolean = false,
) {

    fun isAvailable(action: ControlAction): Boolean = when (action.nativeIdentity()) {
        ControlAction.VideoQuality -> isEpisodeOnline == true
        ControlAction.Chapters -> hasChapterIndicator
        ControlAction.PictureInPicture -> isPipAvailable
        ControlAction.Cast -> isCastAvailable
        else -> true
    }

    /**
     * Whether a region should emit a slot for [binding] at all.
     *
     * [isEditing] covers both the editor and an arrange in progress: a stored
     * position has to stay visible, draggable and editable there even when the
     * video it would act on has nothing for it, or a slot would become
     * unreachable for as long as its condition is false.
     */
    fun isRendered(binding: ControlBinding, isEditing: Boolean): Boolean =
        isEditing || isAvailable(binding.tap)
}

/**
 * The action a binding stands for, which for a seeded default is the native
 * action rather than the Lua row it is stored as. Every native default is a
 * [ControlAction.CustomButton] with a negative id by the time a region sees it
 * (see ControlBindingPreferences.applyButtonOptions), so matching on the action
 * objects alone would never match these four again.
 */
private fun ControlAction.nativeIdentity(): ControlAction = when (this) {
    is ControlAction.CustomButton -> ControlAction.nativeActionForButtonId(id) ?: this
    else -> this
}
// <-- AM (CONTROL_BINDINGS)
