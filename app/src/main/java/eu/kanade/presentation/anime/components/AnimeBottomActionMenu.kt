package eu.kanade.presentation.anime.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

// AM (BOTTOM_ACTION_OVERFLOW) -->
/** Where an action wants to live, independent of how much room the bar has. */
enum class BottomActionPlacement {
    /**
     * Never leaves the bar. For an action whose content anchors a dropdown to
     * the button's box — that anchor has nowhere to attach from inside a menu.
     */
    Pinned,

    /** A button while the bar has room for it, otherwise into the overflow. */
    Auto,

    /**
     * Always in the overflow menu, however wide the bar is. For an action that
     * is deliberately tucked away rather than merely low priority.
     */
    Overflow,
}

/**
 * One action in a bottom action bar.
 *
 * [key] identifies the action for the long-press label state. It is deliberately
 * not the list position: actions appear and disappear with the selection, so a
 * positional index silently attaches the revealed label to whichever button
 * happens to slide into that slot.
 */
@Immutable
class BottomActionItem(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val placement: BottomActionPlacement = BottomActionPlacement.Auto,
    val content: (@Composable () -> Unit)? = null,
)

/**
 * The shared bottom action bar. Owns the scaffold (surface, insets, long-press
 * label) and decides how many actions fit, so the bars above only describe what
 * their actions are.
 *
 * Nothing here confirms anything despite the historical naming: a long press
 * reveals the button's own title for a second and widens it, and the click is
 * unguarded either way. An action loses nothing by overflowing — a menu row
 * carries that same title permanently.
 */
@Composable
private fun BottomActionBar(
    visible: Boolean,
    actions: List<BottomActionItem>,
    modifier: Modifier = Modifier,
    enter: EnterTransition = expandVertically(expandFrom = Alignment.Bottom),
    exit: ExitTransition = shrinkVertically(shrinkTowards = Alignment.Bottom),
    // Each bar kept the insets modifier it already had. The two forms differ in
    // whether they consume the inset for descendants, so swapping one for the
    // other risks double padding or none at all depending on what an ancestor
    // did — not something to change while moving the code.
    insetsModifier: Modifier = Modifier.padding(
        WindowInsets.navigationBars
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues(),
    ),
) {
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
    ) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val scope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            var revealedKey by remember { mutableStateOf<String?>(null) }
            var revealJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (String) -> Unit = { key ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                revealedKey = key
                revealJob?.cancel()
                revealJob = scope.launch {
                    delay(1.seconds)
                    if (isActive && revealedKey == key) revealedKey = null
                }
            }

            BoxWithConstraints(
                modifier = insetsModifier.padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                // How many 48.dp targets fit the width this bar actually got,
                // which on a two-pane layout is a fraction of the window.
                val slots = (this.maxWidth / MinButtonWidth).toInt().coerceAtLeast(1)
                val (barActions, overflowActions) = splitForOverflow(actions, slots)

                Row(modifier = Modifier.fillMaxWidth()) {
                    barActions.forEach { action ->
                        Button(
                            title = action.title,
                            icon = action.icon,
                            labelRevealed = revealedKey == action.key,
                            onLongClick = { onLongClickItem(action.key) },
                            onClick = action.onClick,
                            content = action.content,
                        )
                    }
                    if (overflowActions.isNotEmpty()) {
                        // Scoped to the overflow button's own presence, so the
                        // menu cannot come back open when a later selection
                        // brings the overflow back.
                        var overflowExpanded by remember { mutableStateOf(false) }
                        Button(
                            title = stringResource(MR.strings.label_more),
                            icon = Icons.Outlined.MoreVert,
                            labelRevealed = false,
                            onLongClick = {},
                            onClick = { overflowExpanded = true },
                        ) {
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                                offset = BottomBarMenuDpOffset,
                            ) {
                                overflowActions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.title) },
                                        onClick = {
                                            overflowExpanded = false
                                            action.onClick()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Splits [actions] into the ones that stay in the bar and the ones that move
 * into the overflow menu, given how many [slots] the bar can hold.
 *
 * Actions placed in the overflow go there whatever the width. The width only
 * decides how many of the remaining ones the bar can still show; those are
 * evicted from the trailing edge, and pinned actions are never evicted. The
 * menu keeps the actions in their original order, so hiding one does not
 * reshuffle the others.
 */
private fun splitForOverflow(
    actions: List<BottomActionItem>,
    slots: Int,
): Pair<List<BottomActionItem>, List<BottomActionItem>> {
    val evicted = actions.indices
        .filterTo(mutableSetOf()) { actions[it].placement == BottomActionPlacement.Overflow }

    // One slot goes to the overflow button itself, but only once something is
    // actually in it.
    fun capacity() = if (evicted.isEmpty()) slots else (slots - 1).coerceAtLeast(0)

    for (index in actions.indices.reversed()) {
        if (actions.size - evicted.size <= capacity()) break
        if (actions[index].placement != BottomActionPlacement.Auto) continue
        evicted += index
    }

    val bar = actions.filterIndexed { index, _ -> index !in evicted }
    val overflow = actions.filterIndexed { index, _ -> index in evicted }
    return bar to overflow
}

private val MinButtonWidth = 48.dp
// <-- AM (BOTTOM_ACTION_OVERFLOW)

@Composable
fun AnimeBottomActionMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onBookmarkClicked: (() -> Unit)? = null,
    onRemoveBookmarkClicked: (() -> Unit)? = null,
    // AY -->
    onFillermarkClicked: (() -> Unit)? = null,
    onRemoveFillermarkClicked: (() -> Unit)? = null,
    // <-- AY
    onMarkAsSeenClicked: (() -> Unit)? = null,
    onMarkAsUnseenClicked: (() -> Unit)? = null,
    onMarkPreviousAsSeenClicked: (() -> Unit)? = null,
    onDownloadClicked: (() -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
    // AY -->
    onExternalClicked: (() -> Unit)? = null,
    onInternalClicked: (() -> Unit)? = null,
    // <-- AY
    // AM (EPISODE_NAMES) -->
    onRenameClicked: (() -> Unit)? = null,
    // <-- AM (EPISODE_NAMES)
) {
    val context = LocalContext.current
    // AY -->
    val playerPreferences = remember { context.appGraph.playerPreferences }
    val alwaysUseExternalPlayer = playerPreferences.alwaysUseExternalPlayer.get()
    // <-- AY

    val actions = buildList {
        if (onBookmarkClicked != null) {
            add(
                BottomActionItem(
                    key = "bookmark",
                    title = stringResource(AYMR.strings.action_bookmark_episode),
                    icon = Icons.Outlined.BookmarkAdd,
                    onClick = onBookmarkClicked,
                ),
            )
        }
        if (onRemoveBookmarkClicked != null) {
            add(
                BottomActionItem(
                    key = "remove_bookmark",
                    title = stringResource(AYMR.strings.action_remove_bookmark_episode),
                    icon = Icons.Outlined.BookmarkRemove,
                    onClick = onRemoveBookmarkClicked,
                ),
            )
        }
        // AY -->
        if (onFillermarkClicked != null) {
            add(
                BottomActionItem(
                    key = "fillermark",
                    title = stringResource(AYMR.strings.action_fillermark_episode),
                    icon = Icons.Outlined.NewLabel,
                    onClick = onFillermarkClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        if (onRemoveFillermarkClicked != null) {
            add(
                BottomActionItem(
                    key = "remove_fillermark",
                    title = stringResource(AYMR.strings.action_remove_fillermark_episode),
                    icon = Icons.AutoMirrored.Outlined.LabelOff,
                    onClick = onRemoveFillermarkClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        // <-- AY
        if (onMarkAsSeenClicked != null) {
            add(
                BottomActionItem(
                    key = "mark_seen",
                    title = stringResource(AMMR.strings.am_action_mark_as_seen),
                    icon = Icons.Outlined.DoneAll,
                    onClick = onMarkAsSeenClicked,
                ),
            )
        }
        if (onMarkAsUnseenClicked != null) {
            add(
                BottomActionItem(
                    key = "mark_unseen",
                    title = stringResource(AMMR.strings.am_action_mark_as_unseen),
                    icon = Icons.Outlined.RemoveDone,
                    onClick = onMarkAsUnseenClicked,
                ),
            )
        }
        if (onMarkPreviousAsSeenClicked != null) {
            add(
                BottomActionItem(
                    key = "mark_previous_seen",
                    title = stringResource(AYMR.strings.action_mark_previous_as_seen),
                    icon = ImageVector.vectorResource(R.drawable.ic_done_prev_24dp),
                    onClick = onMarkPreviousAsSeenClicked,
                ),
            )
        }
        if (onDownloadClicked != null) {
            add(
                BottomActionItem(
                    key = "download",
                    title = stringResource(MR.strings.action_download),
                    icon = Icons.Outlined.Download,
                    onClick = onDownloadClicked,
                ),
            )
        }
        if (onDeleteClicked != null) {
            add(
                BottomActionItem(
                    key = "delete",
                    title = stringResource(MR.strings.action_delete),
                    icon = Icons.Outlined.Delete,
                    onClick = onDeleteClicked,
                ),
            )
        }
        // AM (EPISODE_NAMES) -->
        // Single-target by nature, so the caller offers it only for a single
        // selection. Lives in the menu rather than the bar: it is rare enough
        // that a slot of its own would cost every other action width.
        if (onRenameClicked != null) {
            add(
                BottomActionItem(
                    key = "rename",
                    title = stringResource(AMMR.strings.am_action_rename_episode),
                    icon = Icons.Outlined.Edit,
                    onClick = onRenameClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        // <-- AM (EPISODE_NAMES)
        // AY -->
        if (onExternalClicked != null && !alwaysUseExternalPlayer) {
            add(
                BottomActionItem(
                    key = "play_externally",
                    title = stringResource(AYMR.strings.action_play_externally),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = onExternalClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        if (onInternalClicked != null && alwaysUseExternalPlayer) {
            add(
                BottomActionItem(
                    key = "play_internally",
                    title = stringResource(AYMR.strings.action_play_internally),
                    icon = Icons.AutoMirrored.Outlined.Input,
                    onClick = onInternalClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        // <-- AY
    }

    BottomActionBar(
        visible = visible,
        actions = actions,
        modifier = modifier,
    )
}

// AM (CUSTOM_EPISODE_ORDER) -->
/**
 * Replaces AnimeBottomActionMenu while reordering. None of the normal bulk
 * actions belong here: each clears the selection when it finishes, which would
 * end the mode mid-edit. Shares the bar above, so it looks and behaves
 * (long-press labels) exactly like the menu it replaces.
 *
 * A null action is omitted, matching the menu's own convention: Change season
 * and Reset order act on the selection so need one, and Discard only appears
 * once something has changed.
 */
@Composable
fun EpisodeReorderBottomBar(
    visible: Boolean,
    onChangeSeasonClicked: (() -> Unit)?,
    onDiscardClicked: (() -> Unit)?,
    onResetClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val actions = buildList {
        if (onChangeSeasonClicked != null) {
            add(
                BottomActionItem(
                    key = "change_season",
                    title = stringResource(AMMR.strings.am_action_change_episode_season),
                    icon = Icons.Default.Layers,
                    onClick = onChangeSeasonClicked,
                ),
            )
        }
        if (onDiscardClicked != null) {
            add(
                BottomActionItem(
                    key = "discard_order",
                    title = stringResource(AMMR.strings.am_action_discard_order_changes),
                    icon = Icons.Outlined.History,
                    onClick = onDiscardClicked,
                ),
            )
        }
        if (onResetClicked != null) {
            add(
                BottomActionItem(
                    key = "reset_order",
                    title = stringResource(AMMR.strings.am_action_reset_episode_order),
                    icon = Icons.Default.RestartAlt,
                    onClick = onResetClicked,
                ),
            )
        }
    }

    BottomActionBar(
        visible = visible,
        actions = actions,
        modifier = modifier,
    )
}
// <-- AM (CUSTOM_EPISODE_ORDER)

@Composable
private fun RowScope.Button(
    title: String,
    icon: ImageVector,
    labelRevealed: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (labelRevealed) 2f else 1f,
        label = "weight",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(animatedWeight)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false),
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
            )

            AnimatedVisibility(
                visible = labelRevealed,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Text(
                    text = title,
                    overflow = TextOverflow.Visible,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        content?.invoke()
    }
}

@Composable
fun LibraryBottomActionMenu(
    visible: Boolean,
    onChangeCategoryClicked: () -> Unit,
    onMarkAsSeenClicked: () -> Unit,
    onMarkAsUnseenClicked: () -> Unit,
    onDownloadClicked: ((DownloadAction) -> Unit)?,
    onDeleteClicked: () -> Unit,
    onMigrateClicked: () -> Unit,
    // AM (MERGED_SOURCES) -->
    onMergeClicked: (() -> Unit)? = null,
    // <-- AM (MERGED_SOURCES)
    modifier: Modifier = Modifier,
) {
    // Hoisted above the bar so the menu's anchor survives recomposition of the
    // action list. That also outlives the bar hiding, so close it explicitly
    // rather than have it reappear over the next selection.
    var downloadExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) downloadExpanded = false
    }

    val actions = buildList {
        add(
            BottomActionItem(
                key = "move_category",
                title = stringResource(MR.strings.action_move_category),
                icon = Icons.AutoMirrored.Outlined.Label,
                onClick = onChangeCategoryClicked,
            ),
        )
        add(
            BottomActionItem(
                key = "mark_seen",
                title = stringResource(AMMR.strings.am_action_mark_as_seen),
                icon = Icons.Outlined.DoneAll,
                onClick = onMarkAsSeenClicked,
            ),
        )
        add(
            BottomActionItem(
                key = "mark_unseen",
                title = stringResource(AMMR.strings.am_action_mark_as_unseen),
                icon = Icons.Outlined.RemoveDone,
                onClick = onMarkAsUnseenClicked,
            ),
        )
        if (onDownloadClicked != null) {
            add(
                BottomActionItem(
                    key = "download",
                    title = stringResource(MR.strings.action_download),
                    icon = Icons.Outlined.Download,
                    onClick = { downloadExpanded = !downloadExpanded },
                    // Its menu anchors to this button's box, so it cannot move
                    // into the overflow.
                    placement = BottomActionPlacement.Pinned,
                    content = {
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = { downloadExpanded = false },
                            onDownloadClicked = onDownloadClicked,
                            offset = BottomBarMenuDpOffset,
                        )
                    },
                ),
            )
        }
        add(
            BottomActionItem(
                key = "migrate",
                title = stringResource(MR.strings.migrate),
                icon = Icons.Outlined.SwapCalls,
                onClick = onMigrateClicked,
                placement = BottomActionPlacement.Overflow,
            ),
        )
        // AM (MERGED_SOURCES) -->
        if (onMergeClicked != null) {
            add(
                BottomActionItem(
                    key = "merge",
                    title = stringResource(AMMR.strings.am_action_merge),
                    icon = Icons.Outlined.CallMerge,
                    onClick = onMergeClicked,
                    placement = BottomActionPlacement.Overflow,
                ),
            )
        }
        // <-- AM (MERGED_SOURCES)
        add(
            BottomActionItem(
                key = "delete",
                title = stringResource(MR.strings.action_delete),
                icon = Icons.Outlined.Delete,
                onClick = onDeleteClicked,
                // The old bar put this in the overflow alongside migrate and
                // merge, but only when the download action happened to be
                // present. Keeping it in the bar is the one deliberate
                // difference: it is a core action, not a niche one.
                placement = BottomActionPlacement.Auto,
            ),
        )
    }

    BottomActionBar(
        visible = visible,
        actions = actions,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
        insetsModifier = Modifier.windowInsetsPadding(
            WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom),
        ),
    )
}

private val BottomBarMenuDpOffset = DpOffset(0.dp, 0.dp)
