// AM (TAG_LIMIT) -->
package eu.kanade.presentation.anime.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import animiru.domain.anime.model.DisplayedTags
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The tag popout: an entry's tags in two sections the user can move tags between.
 *
 * Visible is literally what the entry shows inline. Moving a tag out shrinks the
 * row rather than promoting another into the gap; moving one in costs a slot, so
 * the lowest-ranked tag still showing drops out and the row keeps the size the
 * global setting asks for. A move pins that tag for good - it stays where the user
 * put it when the setting later changes, and Reset is what hands the whole entry
 * back to the setting. See DisplayedTags.from for the exact rule.
 *
 * Moving is a selection mode, entered by long-pressing a tag, in which this
 * sheet's own header is replaced by the actions - the same shape as episode
 * selection, and the reason the chips carry no per-tag buttons. Moving several at
 * once is also one transaction rather than one write per tag.
 *
 * [sourceCounts] maps a lowercased tag to how many of a merged entry's sources
 * list it - the count its ranking is built on. It is empty for an ordinary entry
 * and for a single-source merge, and a tag only one source lists shows no number,
 * since "+1" says nothing.
 *
 * Laying out every chip at once is the cost the cap exists to avoid, so it is paid
 * here only, on an explicit tap, inside a sheet that scrolls rather than in the
 * entry screen's own layout.
 */
@Composable
fun TagVisibilityDialog(
    displayedTags: DisplayedTags,
    sourceCounts: Map<String, Int>,
    addedTags: Set<String>,
    canSearchPlaylist: Boolean,
    onDismissRequest: () -> Unit,
    onSetTagsVisible: (tags: Collection<String>, visible: Boolean) -> Unit,
    onPinTags: (tags: Collection<String>) -> Unit,
    onUnpinTags: (tags: Collection<String>) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTags: (tags: Collection<String>) -> Unit,
    onResetTagVisibility: () -> Unit,
    onTagSearch: (String) -> Unit,
    onTagGlobalSearch: (String) -> Unit,
    onTagEpisodeSearch: (String) -> Unit,
    onCopyTagToClipboard: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var tagSelected by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showAddTag by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val inSelectionMode = selection.isNotEmpty()
    // Offered only once the list is long enough to be worth searching. Below that the
    // field would take the header's width to save nobody any scrolling.
    val searchable = displayedTags.visible.size + displayedTags.hidden.size > MinTagsToSearch

    // Back leaves selection mode before it closes the sheet, so an accidental
    // long-press costs one back press rather than the whole popout.
    BackHandler(enabled = inSelectionMode) { selection = emptySet() }

    AdaptiveSheet(
        onDismissRequest = { if (inSelectionMode) selection = emptySet() else onDismissRequest() },
        // A quarter of the screen: the drag a half-screen menu already needed, which
        // is half what a full-screen one used to. Tied to the screen rather than a
        // fixed dp so it is the same fraction of a thumb's reach on any device, and
        // so a menu at or under half the screen is unchanged.
        dismissThreshold = LocalConfiguration.current.screenHeightDp.dp / DismissScreenDivisor,
        // The sheet holds a search field, so its base rises with the keyboard and the
        // tags stay visible above it while typing.
        fitsKeyboard = true,
    ) {
        // The handle and header sit OUTSIDE the scrolling part, which is what makes
        // this closable. AdaptiveSheet's swipe-to-dismiss only takes over from a
        // scrolling child once that child is at its top, so with the whole sheet in
        // one scroller a downward drag spent itself scrolling the list back up
        // before any of it counted towards a dismiss - that is the distance the user
        // was fighting, not the sheet's own 56dp threshold. A drag on the fixed top
        // goes to the sheet immediately, whatever the list below is doing.
        //
        // The sheet is deliberately NOT capped shorter than its content: shrinking
        // it was not the ask, and the dismiss no longer depends on a visible scrim.
        Column(modifier = Modifier.padding(bottom = MaterialTheme.padding.medium)) {
            DragHandle()
            if (inSelectionMode) {
                // Only the edits that would do something: a selection entirely in
                // Visible has nothing to show, one that is entirely pinned has
                // nothing to pin. Offering an action that is a no-op for every tag
                // selected invites the user to test what it does.
                val visibleTags = remember(displayedTags) { displayedTags.visible.toSet() }
                TagSelectionBar(
                    selectedCount = selection.size,
                    canShow = selection.any { it !in visibleTags },
                    canHide = selection.any { it in visibleTags },
                    canPin = selection.any { it.lowercase() !in displayedTags.pinned },
                    canUnpin = selection.any { it.lowercase() in displayedTags.pinned },
                    canDelete = selection.any { it.lowercase() in addedTags },
                    onClearSelection = { selection = emptySet() },
                    onShow = {
                        onSetTagsVisible(selection, true)
                        selection = emptySet()
                    },
                    onHide = {
                        onSetTagsVisible(selection, false)
                        selection = emptySet()
                    },
                    onPin = {
                        onPinTags(selection)
                        selection = emptySet()
                    },
                    onUnpin = {
                        onUnpinTags(selection)
                        selection = emptySet()
                    },
                    onDelete = {
                        onDeleteTags(selection)
                        selection = emptySet()
                    },
                )
            } else {
                TagSheetHeader(
                    isCustomised = displayedTags.isCustomised,
                    query = query.takeIf { searchable },
                    onQueryChange = { query = it },
                    onAddTag = { showAddTag = true },
                    onReset = onResetTagVisibility,
                )
            }

            if (showAddTag) {
                AddTagDialog(
                    onDismissRequest = { showAddTag = false },
                    onConfirm = {
                        onAddTag(it)
                        showAddTag = false
                    },
                )
            }

            TagActionsMenu(
                expanded = showMenu,
                canSearchPlaylist = canSearchPlaylist,
                onDismissRequest = { showMenu = false },
                onSearch = {
                    onTagSearch(tagSelected)
                    onDismissRequest()
                },
                onGlobalSearch = {
                    onTagGlobalSearch(tagSelected)
                    onDismissRequest()
                },
                onEpisodeSearch = {
                    onTagEpisodeSearch(tagSelected)
                    onDismissRequest()
                },
                onCopyToClipboard = {
                    onCopyTagToClipboard(tagSelected)
                    onDismissRequest()
                },
            )

            // Tapping means the tag menu until a selection exists, and toggling
            // once one does - so a selection is never lost to a stray tap that
            // opened a menu instead.
            val onTagClick: (String) -> Unit = { tag ->
                if (inSelectionMode) {
                    selection = if (tag in selection) selection - tag else selection + tag
                } else {
                    tagSelected = tag
                    showMenu = true
                }
            }
            val onTagLongClick: (String) -> Unit = { tag ->
                selection = if (tag in selection) selection - tag else selection + tag
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TagVisibilitySection(
                    title = stringResource(AMMR.strings.am_entry_tags_visible),
                    tags = displayedTags.visible.filterByQuery(query),
                    sourceCounts = sourceCounts,
                    pinned = displayedTags.pinned,
                    selection = selection,
                    emptyText = stringResource(AMMR.strings.am_entry_tags_none_visible),
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick,
                )

                TagVisibilitySection(
                    title = stringResource(AMMR.strings.am_entry_tags_hidden),
                    tags = displayedTags.hidden.filterByQuery(query),
                    sourceCounts = sourceCounts,
                    pinned = displayedTags.pinned,
                    selection = selection,
                    emptyText = stringResource(AMMR.strings.am_entry_tags_none_hidden),
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick,
                )
            }
        }
    }
}

/**
 * The bar to drag the sheet away by, and the sign that dragging works. It sits in
 * the sheet's fixed top so the gesture never has to wait for a scroll to reach its
 * own top first.
 */
@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = HandleWidth, height = HandleHeight)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HandleAlpha),
                    shape = CircleShape,
                ),
        )
    }
}

/**
 * The sheet's title row, or - once there are enough tags to be worth searching - the
 * search field in the title's place, as the entry screen puts its episode search in
 * the middle of its own bar. Always open rather than behind a toggle: it is only
 * offered when the list is long, and a long list is one you are going to search.
 *
 * [query] is null where the list is too short to search.
 */
@Composable
private fun TagSheetHeader(
    isCustomised: Boolean,
    query: String?,
    onQueryChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.small,
            top = MaterialTheme.padding.small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (query == null) {
            Text(
                text = stringResource(AMMR.strings.am_entry_tags),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = MaterialTheme.padding.extraSmall)
                                .size(SearchIconSize),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(AMMR.strings.am_entry_tags_search),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.secondaryItemAlpha(),
                                )
                            }
                            field()
                        }
                    }
                },
            )
        }
        // Offered only where there is something to undo, so it never reads as an
        // action that would change an untouched entry.
        if (isCustomised) {
            TextButton(onClick = onReset) {
                Text(text = stringResource(MR.strings.action_reset))
            }
        }
        // Top right, and only out of selection mode - in it the bar's own actions
        // own that corner, and adding a tag has nothing to do with a selection.
        IconButton(onClick = onAddTag) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(AMMR.strings.am_action_add_tag),
            )
        }
    }
}

/**
 * Asks for the tag to add. Confirm is disabled while the field is empty rather
 * than accepting blank input and quietly doing nothing.
 */
@Composable
private fun AddTagDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AMMR.strings.am_action_add_tag)) },
        text = {
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it },
                label = { Text(text = stringResource(AMMR.strings.am_entry_tag_name)) },
                singleLine = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tag) },
                enabled = tag.isNotBlank(),
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
    )
}

/**
 * Replaces the sheet's header while tags are selected: dismiss, the count, then
 * whichever of the four edits apply to what is selected.
 *
 * Moving and pinning are both offered because they are different edits: Show and
 * Hide say where a tag goes, Pin says "keep this one where it is" for a tag the cap
 * is only letting through today, and Unpin hands one back to the cap. A mixed
 * selection can legitimately show all four.
 *
 * The icons carry no tint. Colour here would have to mean something, and these
 * four sit beside each other as peers - the icon says which edit it is, the same
 * way every other action bar in the app works.
 */
@Composable
private fun TagSelectionBar(
    selectedCount: Int,
    canShow: Boolean,
    canHide: Boolean,
    canPin: Boolean,
    canUnpin: Boolean,
    canDelete: Boolean,
    onClearSelection: () -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(
            horizontal = MaterialTheme.padding.small,
            vertical = MaterialTheme.padding.extraSmall,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(MR.strings.action_cancel),
            )
        }
        Text(
            text = selectedCount.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (canPin) {
            IconButton(onClick = onPin) {
                // Filled is pinned and outlined is not, as the sources list already
                // uses them, so each icon shows the state its action leads to.
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(AMMR.strings.am_action_pin_tag),
                )
            }
        }
        if (canUnpin) {
            IconButton(onClick = onUnpin) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = stringResource(AMMR.strings.am_action_unpin_tag),
                )
            }
        }
        if (canShow) {
            IconButton(onClick = onShow) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = stringResource(AMMR.strings.am_action_show_tag),
                )
            }
        }
        if (canHide) {
            IconButton(onClick = onHide) {
                Icon(
                    imageVector = Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(AMMR.strings.am_action_hide_tag),
                )
            }
        }
        // Only where the selection holds a tag of the user's own. A source's tag
        // has no delete: it would be back on the next refresh.
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}

@Composable
private fun TagVisibilitySection(
    title: String,
    tags: List<String>,
    sourceCounts: Map<String, Int>,
    pinned: Set<String>,
    selection: Set<String>,
    emptyText: String,
    onTagClick: (String) -> Unit,
    onTagLongClick: (String) -> Unit,
) {
    // One gap, declared once. Every vertical space here comes from this Column's
    // spacing or the FlowRow's verticalArrangement - a chip carries no padding of
    // its own, which is what made the rows sit unevenly.
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.medium,
                top = MaterialTheme.padding.medium,
            ),
        )
        if (tags.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .secondaryItemAlpha()
                    .padding(horizontal = MaterialTheme.padding.medium),
            )
            return@Column
        }
        FlowRow(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            tags.forEach { tag ->
                SelectableTag(
                    tag = tag,
                    // A tag only one source lists says nothing by saying "+1".
                    sourceCount = sourceCounts[tag.lowercase()]?.takeIf { it > 1 },
                    pinned = tag.lowercase() in pinned,
                    selected = tag in selection,
                    onClick = { onTagClick(tag) },
                    onLongClick = { onTagLongClick(tag) },
                )
            }
        }
    }
}

/**
 * A tag and its source count, as a chip that also takes a long press.
 *
 * Built on [Surface] rather than Material's InputChip because a chip owns its own
 * `clickable` internally: a `combinedClickable` wrapped around one never sees the
 * press, since the inner target consumes it, and putting the gesture on the label
 * instead would leave the chip's padding pressable but dead. One surface with one
 * gesture modifier keeps a single target that handles both.
 *
 * Three states, all drawn by fill and border alone so the chip never changes size:
 * an ordinary tag is an outline, a [pinned] one is filled, and a [selected] one
 * takes the primary colours and a thicker border. Nothing is added inside the
 * chip - a leading check or pin icon reflows the row every time a tag is tapped,
 * which reads worse than the state it announces.
 *
 * [pinned] is what the sections cannot show on their own: a tag sitting in Visible
 * because the user put it there looks otherwise identical to one the cap let
 * through, and only the first survives the setting changing.
 *
 * The count is a dimmed `+N` span inside the label, matching the "more" chip's
 * reading of a number as "this many further", so it stays metadata rather than
 * part of the tag's name.
 */
@Composable
private fun SelectableTag(
    tag: String,
    sourceCount: Int?,
    pinned: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        pinned -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val countColor = contentColor.copy(alpha = CountAlpha)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            pinned -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        },
        contentColor = contentColor,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                pinned -> MaterialTheme.colorScheme.outlineVariant
                else -> MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        // The Surface above is decoration only - no onClick on it. Giving it one
        // would put a second click target around this Row's and consume the press
        // before the long press could be recognised, which is the whole reason
        // this is not a Material chip.
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .defaultMinSize(minHeight = ChipHeight)
                .padding(horizontal = MaterialTheme.padding.mediumSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = remember(tag, sourceCount, countColor) {
                    buildAnnotatedString {
                        append(tag)
                        if (sourceCount != null) {
                            append("  ")
                            withStyle(SpanStyle(color = countColor)) {
                                append("+")
                                append(sourceCount.toString())
                            }
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The sheet never asks for more than a screen height over this to close it, however
 * tall it grows: four means a quarter of the screen.
 *
 * Raise it to close with less drag, lower it to demand more. Nothing else in the app
 * reads it.
 */
private const val DismissScreenDivisor = 4

/** Matches a tag chip's own text, so the field does not tower over the list. */
private val SearchIconSize = 18.dp

/** Above this many tags the sheet offers a search field in place of its title. */
private const val MinTagsToSearch = 20

/**
 * Case-insensitive substring match, which is what a tag list needs: the point is
 * finding "action" among fifty, not ranking near-misses.
 */
private fun List<String>.filterByQuery(query: String): List<String> {
    if (query.isBlank()) return this
    return filter { it.contains(query.trim(), ignoreCase = true) }
}

private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
private const val HandleAlpha = .4f

/** Material's own chip height, so these sit level with the entry screen's tags. */
private val ChipHeight = 32.dp

private const val CountAlpha = .7f
// <-- AM (TAG_LIMIT)
