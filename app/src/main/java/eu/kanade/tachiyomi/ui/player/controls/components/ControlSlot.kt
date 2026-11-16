package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import animiru.domain.player.model.ControlSlotId
import tachiyomi.presentation.core.components.material.padding
import kotlin.math.abs

// AM (CONTROL_BINDINGS) -->
@Composable
fun EmptySlotContent(
    iconSize: Dp = 20.dp,
    horizontalSpacing: Dp = MaterialTheme.padding.medium,
    verticalSpacing: Dp = MaterialTheme.padding.medium,
) {
    Box(
        modifier = Modifier.padding(horizontal = horizontalSpacing, vertical = verticalSpacing),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun StaticSlot(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.border(
            BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            SLOT_SHAPE,
        ),
    ) {
        content()
    }
}

private val SLOT_SHAPE = RoundedCornerShape(4.dp)

/** One queue-style item for a real stored position or an available free position. */
data class ControlSlotMove(
    val position: ControlSlotId,
    val source: ControlSlotId,
)

data class ReorderableControlSlot(
    val id: ControlSlotId,
    val width: Dp,
    val arrangingWidth: Dp = width,
    val onEdit: (() -> Unit)? = null,
    val canDrag: Boolean = false,
    val showBorder: Boolean = true,
    val onArrangeStart: () -> Unit = {},
    val content: @Composable () -> Unit,
)

private val ReorderableControlSlot.lazyKey: String
    get() = "${id.region.name}:${id.index}"

/**
 * The same reorderable implementation as the download queue, shared by every row.
 * Visual and persisted moves are both positional swaps, never remove-and-insert.
 * Free positions stay in the list at zero width before a hold, so they become valid
 * drag targets as soon as arrange mode reveals them without replacing the held item.
 */
@Composable
fun ReorderableControlRow(
    slots: List<ReorderableControlSlot>,
    isEditing: Boolean,
    isArranging: Boolean,
    onMove: (List<ControlSlotMove>) -> Unit,
    modifier: Modifier = Modifier,
    horizontalArrangement: (List<ReorderableControlSlot>) -> Arrangement.Horizontal = { Arrangement.Start },
) {
    val density = LocalDensity.current
    val currentSlots by rememberUpdatedState(slots)
    val currentOnMove by rememberUpdatedState(onMove)
    val localSlots = remember { mutableStateListOf<ReorderableControlSlot>() }
    val centers = remember { mutableStateMapOf<String, Float>() }
    var heldKey by remember { mutableStateOf<String?>(null) }
    val dragStartSlots = remember { mutableListOf<ReorderableControlSlot>() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val displayedSlots = if (heldKey == null) slots else localSlots
    val rowArrangement = horizontalArrangement(displayedSlots)

    Row(
        modifier = modifier,
        // A drag can reveal free positions without replacing its local snapshot. Build
        // the arrangement from the same list the Row measures, not the newer parent list.
        horizontalArrangement = rowArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        displayedSlots.forEach { slot ->
            key(slot.lazyKey) {
                val held = heldKey == slot.lazyKey
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer { translationX = if (held) dragOffset else 0f }
                        .onGloballyPositioned {
                            if (heldKey != slot.lazyKey) {
                                centers[slot.lazyKey] = it.positionInParent().x + it.size.width / 2f
                            }
                        }
                        .width(if (isArranging) slot.arrangingWidth else slot.width)
                        .then(
                            if ((isEditing || isArranging) && slot.showBorder) {
                                Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)), SLOT_SHAPE)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    slot.content()

                    // In the editor the slot owns input, not the playback control drawn
                    // inside it. This later, hit-tested overlay prevents combinedClickable
                    // children from winning the long press; every region and every visual
                    // placement therefore uses exactly the same drag recognizer.
                    if (slot.canDrag || slot.onEdit != null) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .then(
                                    if (slot.canDrag) {
                                        Modifier.pointerInput(slot.lazyKey) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    localSlots.clear()
                                                    localSlots.addAll(currentSlots)
                                                    dragStartSlots.clear()
                                                    dragStartSlots.addAll(currentSlots)
                                                    heldKey = slot.lazyKey
                                                    dragOffset = 0f
                                                    slot.onArrangeStart()
                                                },
                                                onDragEnd = {
                                                    // Commit the final visible placement in one write. Source
                                                    // ids name original entries, not stale intermediate indices.
                                                    currentOnMove(
                                                        localSlots.mapIndexed { index, source ->
                                                            ControlSlotMove(
                                                                position = dragStartSlots[index].id,
                                                                source = source.id,
                                                            )
                                                        },
                                                    )
                                                    dragStartSlots.clear()
                                                    heldKey = null
                                                },
                                                onDragCancel = {
                                                    dragStartSlots.clear()
                                                    heldKey = null
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.x
                                                    var moving = true
                                                    while (moving) {
                                                        // heldKey, not the composable's captured slot
                                                        // parameter, owns this gesture's identity.
                                                        val activeKey = heldKey
                                                        if (activeKey == null) {
                                                            moving = false
                                                            continue
                                                        }
                                                        val from = localSlots.indexOfFirst { it.lazyKey == activeKey }
                                                        if (from == -1) {
                                                            moving = false
                                                            continue
                                                        }
                                                        val direction = if (dragOffset > 0) 1 else -1
                                                        val target = localSlots.getOrNull(from + direction)
                                                        if (target == null) {
                                                            moving = false
                                                            continue
                                                        }
                                                        val sourceCenter = centers[activeKey]
                                                        val targetCenter = centers[target.lazyKey]
                                                        val fallback = with(density) {
                                                            val width = if (isArranging) {
                                                                target.arrangingWidth
                                                            } else {
                                                                target.width
                                                            }
                                                            width.toPx() + rowArrangement.spacing.toPx()
                                                        }
                                                        val distance = if (sourceCenter != null && targetCenter != null) {
                                                            abs(targetCenter - sourceCenter)
                                                        } else {
                                                            fallback
                                                        }
                                                        if (distance <= 0f || abs(dragOffset) < distance) {
                                                            moving = false
                                                            continue
                                                        }
                                                        val source = localSlots[from]
                                                        localSlots[from] = target
                                                        localSlots[from + direction] = source
                                                        if (targetCenter != null) centers[activeKey] = targetCenter
                                                        dragOffset -= direction * distance
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (slot.onEdit != null) {
                                        Modifier.clickable { slot.onEdit.invoke() }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}
// <-- AM (CONTROL_BINDINGS)
