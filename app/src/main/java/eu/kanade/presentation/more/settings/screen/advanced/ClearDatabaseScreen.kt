package eu.kanade.presentation.more.settings.screen.advanced

// AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// AM (MERGED_SOURCES) -->
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.queue.repository.QueueRepository
// <-- AM (MERGED_SOURCES)
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.FetchType
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import eu.kanade.tachiyomi.util.storage.toSize
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
// AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.data.Database
import tachiyomi.data.source.mapSourceToDomainSource
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryAnime
import tachiyomi.domain.source.model.Source
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import tachiyomi.domain.source.model.SourceCoverageUsage
import tachiyomi.domain.source.model.SourceWithIds
import tachiyomi.domain.source.model.StubSource
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import tachiyomi.domain.source.repository.SourceCoverageRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

class ClearDatabaseScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<ClearDatabaseViewModel>()
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val s = state) {
            is ClearDatabaseViewModel.State.Loading -> LoadingScreen()
            is ClearDatabaseViewModel.State.Ready -> {
                if (s.showConfirmation) {
                    var keepSeenAnime by remember { mutableStateOf(true) }
                    AlertDialog(
                        title = {
                            Text(text = stringResource(MR.strings.are_you_sure))
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                Text(text = stringResource(MR.strings.clear_database_text))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(AMMR.strings.am_clear_db_exclude_seen),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = keepSeenAnime,
                                        onCheckedChange = { keepSeenAnime = it },
                                    )
                                }
                                if (!keepSeenAnime) {
                                    Text(
                                        text = stringResource(AMMR.strings.am_clear_database_history_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        onDismissRequest = model::hideConfirmation,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launchUI {
                                        model.removeAnimeBySourceId(keepSeenAnime)
                                        model.clearSelection()
                                        model.hideConfirmation()
                                        context.toast(MR.strings.clear_database_completed)
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = model::hideConfirmation) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }

                // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
                // What the dump actually did. Shown rather than toasted,
                // because the figures are the reason for doing it: this table
                // is bounded by listings x deepest page reached, so a healthy
                // install holds very little, and a number that is suddenly
                // large is the signal that the recording has broken.
                s.dumpResult?.let { result ->
                    AlertDialog(
                        title = { Text(text = stringResource(AMMR.strings.am_viewed_sequences_dumped)) },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                Text(
                                    text = stringResource(
                                        AMMR.strings.am_viewed_sequences_dumped_detail,
                                        result.pages,
                                        result.bytes.toSize(),
                                    ),
                                )
                                // Said plainly rather than left to be inferred
                                // from a file size that did not move: deleting
                                // rows returns their pages to SQLite's freelist
                                // for reuse, and the file shrinks on a vacuum.
                                Text(
                                    text = stringResource(
                                        AMMR.strings.am_viewed_sequences_database_size,
                                        result.databaseSize.toSize(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onDismissRequest = model::dismissDumpResult,
                        confirmButton = {
                            TextButton(onClick = model::dismissDumpResult) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                    )
                }
                // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)

                // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
                // What one source holds, and which of it to delete.
                //
                // Tapping a row used to toggle it into the selection, which
                // made the one gesture the list answers to the bulk one - and
                // the bulk action deletes a single fixed thing. A source holds
                // three separable kinds of data and only one of them was ever
                // reachable.
                s.perSource?.let { perSource ->
                    val source = s.items.find { it.id == perSource.sourceId } ?: return@let
                    val coverage = s.coverageBySourceId[perSource.sourceId]
                    val libraryCount = s.libraryIdsBySourceId[perSource.sourceId]?.size ?: 0
                    AlertDialog(
                        title = { Text(text = source.source.visualName) },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                PerSourceToggle(
                                    label = stringResource(
                                        AMMR.strings.am_clear_source_sequences,
                                        coverage?.pageCount ?: 0L,
                                    ),
                                    checked = perSource.sequences,
                                    // Nothing recorded is not a choice to
                                    // offer; the row says so too.
                                    enabled = coverage != null,
                                    onCheckedChange = model::togglePerSourceSequences,
                                )
                                PerSourceToggle(
                                    label = stringResource(
                                        AMMR.strings.am_clear_source_non_library,
                                        source.count,
                                    ),
                                    checked = perSource.nonLibrary,
                                    enabled = source.count > 0,
                                    onCheckedChange = model::togglePerSourceNonLibrary,
                                )
                                PerSourceToggle(
                                    label = stringResource(
                                        AMMR.strings.am_clear_source_library,
                                        libraryCount,
                                    ),
                                    checked = perSource.library,
                                    enabled = libraryCount > 0,
                                    onCheckedChange = model::togglePerSourceLibrary,
                                )
                                // Shown only once the destructive choice is
                                // actually made, rather than standing in the
                                // dialog as boilerplate nobody reads. This is
                                // the one path in the app that deletes entries
                                // the user chose to keep.
                                if (perSource.library) {
                                    Text(
                                        text = stringResource(AMMR.strings.am_clear_source_library_warning),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onDismissRequest = model::dismissPerSource,
                        confirmButton = {
                            // Absent rather than disabled until something is
                            // chosen: a dialog whose only live control is
                            // Cancel is clearer without a button that looks
                            // like the way forward.
                            if (perSource.hasSelection) {
                                TextButton(
                                    onClick = {
                                        scope.launchUI { model.runPerSource() }
                                    },
                                ) {
                                    Text(text = stringResource(MR.strings.action_ok))
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = model::dismissPerSource) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }
                // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(MR.strings.pref_clear_database),
                            navigateUp = navigator::pop,
                            actions = {
                                if (s.items.isNotEmpty()) {
                                    AppBarActions(
                                        actions = buildList {
                                            add(
                                                AppBar.Action(
                                                    title = stringResource(MR.strings.action_select_all),
                                                    icon = Icons.Outlined.SelectAll,
                                                    onClick = model::selectAll,
                                                ),
                                            )
                                            add(
                                                AppBar.Action(
                                                    title = stringResource(MR.strings.action_select_inverse),
                                                    icon = Icons.Outlined.FlipToBack,
                                                    onClick = model::invertSelection,
                                                ),
                                            )
                                            // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
                                            // A second verb over the same list
                                            // and the same selection, rather
                                            // than a screen of its own: these
                                            // rows are per-source app data, so
                                            // the place that deletes per-source
                                            // app data is where they belong.
                                            // In the overflow because it is the
                                            // lesser of the two actions - what
                                            // it drops is re-learned by
                                            // browsing, at the price of the
                                            // requests that browsing could
                                            // otherwise have skipped.
                                            //
                                            // Offered only when the selection
                                            // actually has something recorded,
                                            // so the entry is never a tap that
                                            // does nothing.
                                            if (s.selection.any { it in s.coverageBySourceId }) {
                                                add(
                                                    AppBar.OverflowAction(
                                                        title = stringResource(
                                                            AMMR.strings.am_action_dump_viewed_sequences,
                                                        ),
                                                        onClick = {
                                                            scope.launchUI {
                                                                model.dumpSequences()
                                                                model.clearSelection()
                                                            }
                                                        },
                                                    ),
                                                )
                                            }
                                            // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
                                        },
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.items.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(MR.strings.database_clean),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_delete),
                            actionEnabled = s.selection.isNotEmpty(),
                            onClickAction = model::showConfirmation,
                        ) {
                            items(s.items) { sourceWithCount ->
                                ClearDatabaseItem(
                                    source = sourceWithCount.source,
                                    count = sourceWithCount.count,
                                    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
                                    coverage = s.coverageBySourceId[sourceWithCount.id],
                                    isSelected = s.selection.contains(sourceWithCount.id),
                                    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
                                    onClick = { model.showPerSource(sourceWithCount.id) },
                                    onLongClick = { model.toggleSelection(sourceWithCount.source) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
    /** One of the three kinds of data a source holds. */
    @Composable
    private fun PerSourceToggle(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)

    @Composable
    private fun ClearDatabaseItem(
        source: Source,
        count: Long,
        // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED): null where this source has
        // recorded nothing, which is most of them.
        coverage: SourceCoverageUsage?,
        isSelected: Boolean,
        // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO): a tap asks what to delete, a
        // hold selects for the bulk action, which is what the tap used to do.
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp)
                // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED): room for the second
                // line where there is one.
                .height(if (coverage == null) 56.dp else 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(source = source)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = source.visualName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(MR.strings.clear_database_source_item_count, count))
                // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
                if (coverage != null) {
                    Text(
                        text = stringResource(
                            AMMR.strings.am_viewed_sequences_source_detail,
                            coverage.pageCount,
                            coverage.listingCount,
                            coverage.estimatedBytes.toSize(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Checkbox(
                checked = isSelected,
                // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO): the checkbox is the
                // selection's own control, so it still toggles it directly -
                // the row around it no longer does.
                onCheckedChange = { onLongClick() },
            )
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ClearDatabaseViewModel(
    // AY -->
    private val getSourcesWithNonLibraryAnime: GetSourcesWithNonLibraryAnime,
    // <-- AY
    // AM (MERGED_SOURCES) -->
    private val mergeChildRepository: MergeChildRepository,
    // <-- AM (MERGED_SOURCES)
    // AM (PLAYBACK_QUEUE)
    private val queueRepository: QueueRepository,
    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
    private val coverageRepository: SourceCoverageRepository,
    private val context: Context,
    private val database: Database,
    private val sourceManager: SourceManager,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow<ClearDatabaseViewModel.State>(State.Loading)

    init {
        viewModelScope.launchIO {
            // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
            // Combined rather than collected separately, so the list and the
            // library counts the dialog reads are always from the same moment -
            // two collectors would let a dialog open on a count that the other
            // half of the screen had already moved past.
            combine(
                getSourcesWithNonLibraryAnime.subscribe(),
                getSourcesWithNonLibraryAnime.subscribeLibrary(),
                ::Pair,
            )
                .collectLatest { (list, libraryList) ->
                    val libraryIdsBySourceId = libraryList
                        .groupBy { it.sourceId }
                        .mapValues { (_, anime) -> anime.map { it.animeId } }
                    // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
                    // AM (MERGED_SOURCES) -->
                    // animedeletableView already excludes merge children, but
                    // the season recursion below walks parent_id and reaches
                    // rows the view never emitted, so it needs the same guard.
                    val mergeChildIds = mergeChildRepository.getAllChildAnimeIds()
                    // <-- AM (MERGED_SOURCES)
                    // AM (PLAYBACK_QUEUE) -->
                    // The queue is the second thing that can depend on a row
                    // the library itself does not hold: queueing from Browse
                    // references an entry that is not favorited, which is
                    // exactly what this screen deletes, and queue_items
                    // cascades with it. Unlike merge children these are NOT
                    // excluded by animedeletableView, so they need guarding at
                    // the top level too, not only in the recursion.
                    val protectedIds = mergeChildIds + queueRepository.getAllQueuedAnimeIds()
                    // <-- AM (PLAYBACK_QUEUE)
                    // AY -->
                    val items = list.groupBy { it.sourceId }
                        .map { (sourceId, deletableAnime) ->
                            val source = sourceManager.getOrStub(sourceId)
                            val domainSource = mapSourceToDomainSource(source).copy(
                                isStub = source is StubSource,
                            )

                            val ids = mutableListOf<Long>()
                            val orphaned = mutableListOf<Long>()

                            deletableAnime.forEach {
                                // AM (PLAYBACK_QUEUE): left out entirely rather
                                // than orphaned - a queued entry is not a season
                                // child being detached from a parent, it is a row
                                // that simply must not go. Keeping it out of `ids`
                                // also keeps it out of the count this screen
                                // offers to delete.
                                if (it.animeId in protectedIds) return@forEach
                                ids.add(it.animeId)
                                if (it.fetchType == FetchType.Seasons) {
                                    val (childrenIds, orphanedIds) =
                                        getDeletableChildren(it.animeId, protectedIds)
                                    ids.addAll(childrenIds)
                                    orphaned.addAll(orphanedIds)
                                }
                            }

                            SourceWithIds(domainSource, ids, orphaned)
                        }
                    // <-- AY

                    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
                    // Read per emission rather than subscribed: these rows
                    // change when something browses, which cannot be happening
                    // while this screen is in front, and when this screen drops
                    // them, which refreshes the list itself.
                    //
                    // A source with recorded pages but nothing deletable is
                    // added rather than left out. It is rare - browsing writes a
                    // row for every card it displays, and those rows are exactly
                    // what this screen deletes - but a source whose entries are
                    // all in the library would otherwise have sequences that
                    // nothing could reach.
                    val coverage = coverageRepository.getRecordedUsage().associateBy { it.sourceId }
                    val listed = items.map { it.id }.toSet()
                    val coverageOnly = coverage.keys
                        .filterNot { it in listed }
                        .map { sourceId ->
                            val source = sourceManager.getOrStub(sourceId)
                            SourceWithIds(
                                mapSourceToDomainSource(source).copy(isStub = source is StubSource),
                                emptyList(),
                                emptyList(),
                            )
                        }
                    // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)

                    state.update { old ->
                        val items = (items + coverageOnly).sortedBy { it.name }
                        when (old) {
                            State.Loading -> State.Ready(
                                items,
                                coverageBySourceId = coverage,
                                // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
                                libraryIdsBySourceId = libraryIdsBySourceId,
                            )
                            is State.Ready -> old.copy(
                                items = items,
                                coverageBySourceId = coverage,
                                // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
                                libraryIdsBySourceId = libraryIdsBySourceId,
                            )
                        }
                    }
                }
        }
    }

    // AY -->

    /**
     * Get all children of an anime that can be deleted, as well as any orphans.
     * Children that are favorited needs their parentId removed or else they won't be
     * able to be removed later.
     */
    // AM (MERGED_SOURCES) -->
    // protectedIds: rows belonging to a merge are undeletable for the same
    // reason animedeletableView excludes them - deleting one cascades its
    // merge_children row away and guts a merged entry that may still be in
    // the library. They're reported as orphaned instead, which detaches them
    // from the season parent being deleted and leaves the row (and the merge)
    // intact, exactly as for a favorited child.
    // <-- AM (MERGED_SOURCES)
    private suspend fun getDeletableChildren(
        animeId: Long,
        // AM (MERGED_SOURCES) -->
        // AM (PLAYBACK_QUEUE): merge children AND queued entries - both are
        // rows something still depends on. Reported as orphaned rather than
        // deleted, which detaches them from the season parent being deleted
        // and leaves the row intact, exactly as for a favorited child.
        protectedIds: Set<Long>,
        // <-- AM (MERGED_SOURCES)
    ): Pair<List<Long>, List<Long>> {
        val ids = mutableListOf<Long>()
        val orphaned = mutableListOf<Long>()
        val children = getSourcesWithNonLibraryAnime.getDeletableChildren(animeId)
        children.forEach { c ->
            // AM (MERGED_SOURCES) -->
            // c.favorite -> c.favorite || c.id in protectedIds.
            if (c.favorite || c.id in protectedIds) {
                // <-- AM (MERGED_SOURCES)
                orphaned.add(c.id)
            } else {
                ids.add(c.id)
                if (c.fetchType == FetchType.Seasons) {
                    // AM (MERGED_SOURCES) -->
                    val (childrenIds, orphanedIds) = getDeletableChildren(c.id, protectedIds)
                    // <-- AM (MERGED_SOURCES)
                    ids.addAll(childrenIds)
                    orphaned.addAll(orphanedIds)
                }
            }
        }
        return Pair(ids, orphaned)
    }
    // <-- AY

    suspend fun removeAnimeBySourceId(keepSeenAnime: Boolean) = withNonCancellableContext {
        val state = state.value as? State.Ready ?: return@withNonCancellableContext
        // AY -->
        val selected = state.items.filter { it.id in state.selection }

        val animeIds = selected.flatMap { it.ids }
        val orphaned = selected.flatMap { it.orphaned }
            .filterNot { it in animeIds }

        database.animesQueries.deleteAnimesNotInLibraryByAnimeIds(animeIds, keepSeenAnime.toLong())
        database.animesQueries.removeParentIdByIds(orphaned)
        // <-- AY
        database.historyQueries.removeResettedHistory()
        // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
        // The selected sources' recorded pages go with their entries, and this
        // is a correctness matter rather than tidiness: a record says how much
        // of a page was VIEWED, and viewedness is read from history. Deleting
        // these entries takes their history with them, so every record of a page
        // they were on now overstates it - which is the same reason a history
        // clear voids all of it (see SourceCoverageRepository.deleteAll).
        selected.forEach { coverageRepository.deleteBySource(it.id) }
        // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
    }

    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
    /**
     * Drops the selected sources' recorded pages, leaving their entries alone.
     *
     * Their listing-health rows are deliberately kept: a health row is not a
     * recorded sequence, it is the record that a listing reorders itself and
     * must not be tracked, and dropping it would make the next walk re-learn
     * that at a page of requests per listing.
     */
    suspend fun dumpSequences() = withNonCancellableContext {
        val current = state.value as? State.Ready ?: return@withNonCancellableContext
        val selected = current.selection.mapNotNull { current.coverageBySourceId[it] }
        if (selected.isEmpty()) return@withNonCancellableContext
        selected.forEach { coverageRepository.deleteBySource(it.sourceId) }
        val result = DumpResult(
            pages = selected.sumOf { it.pageCount },
            bytes = selected.sumOf { it.estimatedBytes },
            // Read after the delete, so the figure above can be read against
            // something real.
            databaseSize = context.getDatabasePath(DATABASE_NAME).length(),
        )
        state.update { old ->
            if (old !is State.Ready) return@update old
            val remaining = old.coverageBySourceId.filterKeys { it !in current.selection }
            old.copy(
                dumpResult = result,
                coverageBySourceId = remaining,
                // A row that was only here for its sequences has nothing left to
                // show, so it goes with them rather than sitting as an empty
                // line until the screen is reopened.
                items = old.items.filterNot { it.ids.isEmpty() && it.id !in remaining },
            )
        }
    }

    fun dismissDumpResult() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(dumpResult = null)
    }
    // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)

    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
    fun showPerSource(sourceId: Long) = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(perSource = PerSource(sourceId = sourceId))
    }

    fun dismissPerSource() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(perSource = null)
    }

    fun togglePerSourceSequences(checked: Boolean) = updatePerSource { it.copy(sequences = checked) }

    fun togglePerSourceNonLibrary(checked: Boolean) = updatePerSource { it.copy(nonLibrary = checked) }

    fun togglePerSourceLibrary(checked: Boolean) = updatePerSource { it.copy(library = checked) }

    private fun updatePerSource(transform: (PerSource) -> PerSource) = state.update { state ->
        if (state !is State.Ready) return@update state
        val perSource = state.perSource ?: return@update state
        state.copy(perSource = transform(perSource))
    }

    /**
     * Deletes what the dialog was told to, for its one source.
     *
     * Order matters. The sequences go first, because the entry deletes below
     * take their history with them and a recorded page's viewed fraction is
     * read FROM history - so a record left behind would overstate pages those
     * entries were on, which is the same reason a history clear voids all of it
     * (SourceCoverageRepository.deleteAll). Deleting them first also means a
     * failure part way through cannot leave records describing rows that are
     * already gone.
     *
     * No keep-seen guard on the non-library delete, unlike the bulk action's:
     * the dialog states a count and the user ticked it. The bulk action keeps
     * its switch.
     */
    suspend fun runPerSource() = withNonCancellableContext {
        val current = state.value as? State.Ready ?: return@withNonCancellableContext
        val perSource = current.perSource ?: return@withNonCancellableContext
        val sourceId = perSource.sourceId
        val item = current.items.find { it.id == sourceId }

        if (perSource.sequences) {
            coverageRepository.deleteBySource(sourceId)
        }
        if (perSource.nonLibrary && item != null) {
            database.animesQueries.deleteAnimesNotInLibraryByAnimeIds(item.ids, 0L)
            database.animesQueries.removeParentIdByIds(item.orphaned)
        }
        if (perSource.library) {
            current.libraryIdsBySourceId[sourceId]
                ?.takeIf { it.isNotEmpty() }
                ?.let { database.animesQueries.deleteAnimesByAnimeIds(it) }
        }
        database.historyQueries.removeResettedHistory()

        state.update { old ->
            if (old !is State.Ready) return@update old
            old.copy(
                perSource = null,
                coverageBySourceId = if (perSource.sequences) {
                    old.coverageBySourceId - sourceId
                } else {
                    old.coverageBySourceId
                },
            )
        }
    }
    // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)

    fun toggleSelection(source: Source) = state.update { state ->
        if (state !is State.Ready) return@update state
        val mutableList = state.selection.toMutableList()
        if (mutableList.contains(source.id)) {
            mutableList.remove(source.id)
        } else {
            mutableList.add(source.id)
        }
        state.copy(selection = mutableList)
    }

    fun clearSelection() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = emptyList())
    }

    fun selectAll() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.items.fastMap { it.id })
    }

    fun invertSelection() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = state.items
                .fastMap { it.id }
                .filterNot { it in state.selection },
        )
    }

    fun showConfirmation() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<SourceWithIds>,
            val selection: List<Long> = emptyList(),
            val showConfirmation: Boolean = false,
            // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
            /** What each source has recorded, for the sources that have. */
            val coverageBySourceId: Map<Long, SourceCoverageUsage> = emptyMap(),
            val dumpResult: DumpResult? = null,
            // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
            // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
            /** Library entry ids per source - what the library toggle deletes. */
            val libraryIdsBySourceId: Map<Long, List<Long>> = emptyMap(),
            /** The open per-source dialog, if one is open. */
            val perSource: PerSource? = null,
            // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
        ) : State
    }

    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
    /** What one dump did, which is what the user asked it for. */
    @Immutable
    data class DumpResult(
        val pages: Long,
        val bytes: Long,
        val databaseSize: Long,
    )

    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
    /** Which of one source's three kinds of data to delete. */
    @Immutable
    data class PerSource(
        val sourceId: Long,
        val sequences: Boolean = false,
        val nonLibrary: Boolean = false,
        val library: Boolean = false,
    ) {
        val hasSelection: Boolean get() = sequences || nonLibrary || library
    }
    // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)

    private companion object {
        /** As AppBindings names it to the SQLDelight driver. */
        const val DATABASE_NAME = "tachiyomi.animedb"
    }
    // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
}
