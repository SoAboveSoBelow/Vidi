package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlSlotId
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.freeSlots
import animiru.domain.player.model.slotCapacity
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.EmptySlotContent
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlRow
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlSlot
import eu.kanade.tachiyomi.ui.player.controls.components.StaticSlot
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import `is`.xyz.mpv.Utils
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

/**
 * AM (CONTROL_BINDINGS): the middle row, rendered from the Middle region.
 *
 * Previous, play and next are entries like any other, so a user can reorder them, drop
 * one, or put something else beside them. The play/pause slot is special only in how it
 * draws - an animated vector that also stands in for the seek indicator and the loading
 * spinner - not in how it dispatches.
 */
@Composable
fun MiddlePlayerControls(
    entries: List<ControlBinding>,

    // middle
    isStopped: Boolean,
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,

    hasPrevious: Boolean,
    hasNext: Boolean,

    // AM (BROWSE_SCROLLER_CONTROLS): the feed draws no transport buttons, only the
    // centre, so the surrounding slots collapse rather than being bound to nothing.
    showMediaButtons: Boolean,

    onAction: (ControlAction) -> Unit,

    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    // AM (CONTROL_BINDINGS): editor mode, as in the other rows.
    onSlotEdit: ((Int) -> Unit)? = null,
    // AM (CONTROL_BINDINGS): hold-and-drag reorder; -1 or 1 per step.
    onSlotMove: ((Int, Int) -> Unit)? = null,
    onArrangeStart: ((Int) -> Unit)? = null,
    /** Opens the picker for a position that does not exist yet, inserting at it. */
    onSlotInsert: ((Int) -> Unit)? = null,
    /**
     * True while any row is being arranged. Every row reveals its free positions, not
     * just the one holding the slot - a control being moved is as likely to be wanted
     * in another bar as further along its own.
     */
    isArranging: Boolean = false,
    heldIndex: Int? = null,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Held as a local because BoxWithConstraints' receiver is not in scope inside
        // with(density) - the Density receiver shadows it.
        val regionWidth = maxWidth
        val slotWidth = MIDDLE_ICON_SIZE + MaterialTheme.padding.medium * 2

        // Play/pause is structural, not a queue item. Each editable side gets exactly
        // half of what remains after the real 96dp centre anchor; orientation changes
        // therefore change capacity rather than selecting a different fixed limit.
        val sideWidth = ((regionWidth - PLAY_PAUSE_SIZE) / 2).coerceAtLeast(0.dp)
        val sideCapacity = with(density) {
            slotCapacity(
                regionWidthPx = sideWidth.roundToPx(),
                anchorsWidthPx = 0,
                minSlotWidthPx = slotWidth.roundToPx(),
            )
        }
        // Stored positions alternate left/right by index parity. Play/pause is not
        // stored here at all, so there is no hidden entry to pad around or migrate in
        // the renderer. The left side is reversed only for middle-out presentation.
        val leftStored = entries.withIndex().filter { it.index % 2 == 0 }
        val rightStored = entries.withIndex().filter { it.index % 2 == 1 }
        fun List<IndexedValue<ControlBinding>>.displayed() =
            if (isArranging) this else filter { it.value.tap != ControlAction.None }
        val leftDisplayed = leftStored.displayed()
        val rightDisplayed = rightStored.displayed()
        // Select from the fixed center outward, THEN trim only the outer end. Never
        // let a narrower orientation choose a different center-adjacent position.
        val leftCenterOut = leftDisplayed.asReversed()
        val rightCenterOut = rightDisplayed
        val leftVisible = leftCenterOut.take(sideCapacity).asReversed()
        val rightVisible = rightCenterOut.take(sideCapacity)
        val everythingVisible = leftStored.size <= sideCapacity && rightStored.size <= sideCapacity
        val leftFreeCount = if (everythingVisible) sideCapacity - leftVisible.size else 0
        val rightFreeCount = if (everythingVisible) sideCapacity - rightVisible.size else 0
        val extraPairs = if (isArranging) minOf(leftFreeCount, rightFreeCount) else 0
        val side = (maxOf(leftVisible.size, rightVisible.size) + extraPairs).coerceAtMost(sideCapacity)
        val leadingFree = side - leftVisible.size
        val trailingFree = side - rightVisible.size
        val leftFree = generateSequence(entries.size) { it + 1 }
            .filter { it % 2 == 0 }
            .take(leadingFree)
            .toList()
            .asReversed()
        val rightFree = generateSequence(entries.size) { it + 1 }
            .filter { it % 2 == 1 }
            .take(trailingFree)
            .toList()

        val positions = side * 2 + 1
        val slotsWidth = PLAY_PAUSE_SIZE + slotWidth * side * 2
        val gap = if (positions > 1) {
            ((regionWidth - slotsWidth) / (positions - 1)).coerceIn(0.dp, MaterialTheme.padding.large)
        } else {
            0.dp
        }

        fun freeSlot(index: Int) = ReorderableControlSlot(
            id = ControlSlotId(ControlRegion.Middle, index),
            width = slotWidth,
            onEdit = onSlotInsert?.let { insert -> { insert(index) } },
            showBorder = isArranging,
        ) {
            if (isArranging) EmptySlotContent(iconSize = MIDDLE_ICON_SIZE)
        }

        fun editableSlot(indexed: IndexedValue<ControlBinding>): ReorderableControlSlot {
            val index = indexed.index
            val entry = indexed.value
            return ReorderableControlSlot(
                id = ControlSlotId(ControlRegion.Middle, index),
                width = slotWidth,
                arrangingWidth = slotWidth,
                onEdit = onSlotEdit?.let { edit -> { edit(index) } },
                canDrag = onSlotEdit != null,
                onArrangeStart = { onArrangeStart?.invoke(index) },
            ) {
                if (entry.tap == ControlAction.None) {
                    EmptySlotContent(iconSize = MIDDLE_ICON_SIZE)
                } else {
                    AnimatedVisibility(
                        visible = controlsShown && !areControlsLocked,
                        enter = enter,
                        exit = exit,
                    ) {
                        if (gestureSeekAmount == null && showMediaButtons) {
                            BoundMiddleButton(
                                entry = entry,
                                hasPrevious = hasPrevious,
                                hasNext = hasNext,
                                onAction = onAction,
                            )
                        }
                    }
                }
            }
        }

        val leftSlots = leftFree.map(::freeSlot) + leftVisible.map(::editableSlot)
        val rightSlots = rightVisible.map(::editableSlot) + rightFree.map(::freeSlot)
        val onMove: (ControlSlotId, ControlSlotId) -> Unit = { from, to ->
            val current = heldIndex ?: from.index
            onSlotMove?.invoke(current, to.index - current)
        }

        // One editable row and one drag state across both sides. The arrangement only
        // reserves the fixed button's center space; it does not split slot identity.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReorderableControlRow(
                slots = leftSlots + rightSlots,
                isEditing = onSlotEdit != null,
                isArranging = isArranging,
                onMove = onMove,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = middleControlsArrangement(
                    leftCount = leftSlots.size,
                    centerWidth = PLAY_PAUSE_SIZE,
                    spacing = gap,
                ),
            )
            val playPause: @Composable () -> Unit = {
                PlayPauseSlot(
                    isStopped = isStopped,
                    isLoading = isLoading,
                    isLoadingEpisode = isLoadingEpisode,
                    controlsShown = controlsShown,
                    areControlsLocked = areControlsLocked,
                    showLoadingCircle = showLoadingCircle,
                    paused = paused,
                    gestureSeekAmount = gestureSeekAmount,
                    onClick = { onAction(ControlAction.PlayPause) },
                    enter = enter,
                    exit = exit,
                )
            }
            if (onSlotEdit != null) StaticSlot { playPause() } else playPause()
        }
    }
}

/** One connected row, packed toward a fixed central anchor from either side. */
private fun middleControlsArrangement(
    leftCount: Int,
    centerWidth: Dp,
    spacing: Dp,
): Arrangement.Horizontal = object : Arrangement.Horizontal {
    override val spacing: Dp = spacing

    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        layoutDirection: LayoutDirection,
        outPositions: IntArray,
    ) {
        val gap = spacing.roundToPx()
        val centerStart = (totalSize - centerWidth.roundToPx()) / 2
        var position = centerStart - gap
        for (index in leftCount - 1 downTo 0) {
            position -= sizes[index]
            outPositions[index] = position
            position -= gap
        }
        position = centerStart + centerWidth.roundToPx() + gap
        for (index in leftCount until sizes.size) {
            outPositions[index] = position
            position += sizes[index] + gap
        }
    }
}

@Composable
private fun BoundMiddleButton(
    entry: ControlBinding,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onAction: (ControlAction) -> Unit,
) {
    val icon = controlActionIcon(entry.tap) ?: return
    // Episode boundaries only gate the actions that cross one.
    val enabled = when (entry.tap) {
        ControlAction.PreviousEpisode -> hasPrevious
        ControlAction.NextEpisode -> hasNext
        else -> true
    }
    ControlsButton(
        icon,
        onClick = { onAction(entry.tap) },
        onLongClick = { entry.longPress?.let(onAction) ?: Unit },
        onDoubleClick = entry.doubleTap?.let { action -> { onAction(action) } },
        iconSize = MIDDLE_ICON_SIZE,
        enabled = enabled,
    )
}

@Composable
private fun PlayPauseSlot(
    isStopped: Boolean,
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,
    onClick: () -> Unit,
    enter: EnterTransition,
    exit: ExitTransition,
) {
    val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
    val interaction = remember { MutableInteractionSource() }
    when {
        isStopped -> {
            Spacer(Modifier.width(PLAY_PAUSE_SIZE))
        }
        gestureSeekAmount != null -> {
            Text(
                stringResource(
                    AYMR.strings.player_gesture_seek_indicator,
                    if (gestureSeekAmount.second >= 0) '+' else '-',
                    Utils.prettyTime(abs(gestureSeekAmount.second)),
                    Utils.prettyTime(gestureSeekAmount.first + gestureSeekAmount.second),
                ),
                style = MaterialTheme.typography.headlineMedium.copy(
                    shadow = Shadow(Color.Black, blurRadius = 5f),
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        (isLoading || isLoadingEpisode) && showLoadingCircle -> CircularProgressIndicator(Modifier.size(PLAY_PAUSE_SIZE))
        else -> {
            AnimatedVisibility(
                visible = controlsShown && !areControlsLocked,
                enter = enter,
                exit = exit,
            ) {
                Image(
                    painter = rememberAnimatedVectorPainter(icon, !paused),
                    modifier = Modifier
                        .size(PLAY_PAUSE_SIZE)
                        .clip(CircleShape)
                        .clickable(
                            interaction,
                            ripple(),
                            onClick = onClick,
                        )
                        .padding(MaterialTheme.padding.medium),
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * What the transport buttons draw at, shared so a free position matches them.
 *
 * The slot's WIDTH is this plus the button's horizontal padding on both sides, which
 * is what the capacity math asks for - a bare touch target understates it by the
 * padding and the row then tries to lay out more slots than fit, which Compose
 * resolves by squashing the last one.
 */
private val MIDDLE_ICON_SIZE = 48.dp

/**
 * What the play control occupies, which is not what the others do.
 *
 * Named so the width math and the control itself cannot drift: charging play as a
 * normal slot understated the row by 16dp, which is how the gap between the three
 * default controls went missing.
 */
private val PLAY_PAUSE_SIZE = 96.dp
