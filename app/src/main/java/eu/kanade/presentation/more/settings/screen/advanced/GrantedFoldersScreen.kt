package eu.kanade.presentation.more.settings.screen.advanced

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

// AM (EXTRA_LOCAL_FOLDERS) -->
/**
 * The folders outside the configured storage directory that the local source
 * covers, with a way to stop covering them.
 *
 * These accumulate on their own - every video opened from a file manager in a
 * folder the user grants adds one, and nothing ever removed one - so there has to
 * be somewhere to see and undo that. Modelled on ClearDatabaseScreen, which is
 * the same shape of job: a list, a selection, and one destructive action behind a
 * confirmation.
 */
class GrantedFoldersScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<GrantedFoldersViewModel>()
        val state by model.state.collectAsState()

        when (val s = state) {
            is GrantedFoldersViewModel.State.Loading -> LoadingScreen()
            is GrantedFoldersViewModel.State.Ready -> {
                if (s.showConfirmation) {
                    AlertDialog(
                        title = { Text(text = stringResource(MR.strings.are_you_sure)) },
                        text = {
                            Text(
                                text = pluralStringResource(
                                    AMMR.plurals.am_granted_folders_remove_text,
                                    count = s.selection.size,
                                    s.selection.size,
                                ),
                            )
                        },
                        onDismissRequest = model::hideConfirmation,
                        confirmButton = {
                            TextButton(onClick = model::removeSelected) {
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

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(AMMR.strings.am_pref_granted_folders),
                            navigateUp = navigator::pop,
                            actions = {
                                if (s.items.isNotEmpty()) {
                                    AppBarActions(
                                        actions = listOf(
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.action_select_all),
                                                onClick = model::selectAll,
                                            ),
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.action_select_inverse),
                                                onClick = model::invertSelection,
                                            ),
                                            // What "remove all not in the library"
                                            // asks for, expressed as a selection so
                                            // the one Remove button still does the
                                            // removing and is still confirmed.
                                            AppBar.OverflowAction(
                                                title = stringResource(
                                                    AMMR.strings.am_granted_folders_select_unused,
                                                ),
                                                onClick = model::selectNotInLibrary,
                                            ),
                                        ),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.items.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(AMMR.strings.am_granted_folders_empty),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_remove),
                            actionEnabled = s.selection.isNotEmpty(),
                            onClickAction = model::showConfirmation,
                        ) {
                            items(s.items, key = { it.uri }) { folder ->
                                GrantedFolderItem(
                                    folder = folder,
                                    isSelected = folder.uri in s.selection,
                                    onClickSelect = { model.toggleSelection(folder.uri) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GrantedFolderItem(
        folder: GrantedFolder,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = MaterialTheme.padding.small)
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (folder.missing) Icons.Outlined.FolderOff else Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (folder.missing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.small)
                    .weight(1f),
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        when {
                            folder.missing -> AMMR.strings.am_granted_folders_unavailable
                            folder.inLibrary -> AMMR.strings.am_granted_folders_in_library
                            else -> AMMR.strings.am_granted_folders_not_in_library
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (folder.inLibrary && !folder.missing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }
}

/**
 * One covered folder as the screen shows it.
 *
 * [uri] is the registered string itself, which is both the identity in the
 * preference and what the local source resolves - never a prettified form of it.
 */
@Immutable
data class GrantedFolder(
    val uri: String,
    val name: String,
    val path: String,
    val inLibrary: Boolean,
    val missing: Boolean,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class GrantedFoldersViewModel(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
    private val localSourceFileSystem: LocalSourceFileSystem,
    private val getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow<State>(State.Loading)

    init {
        viewModelScope.launchIO {
            storagePreferences.extraLocalFolders.changes()
                .onStart { emit(storagePreferences.extraLocalFolders.get()) }
                .collectLatest { registered ->
                    val items = registered.sorted().map { uri -> describe(uri) }
                    state.update { old ->
                        when (old) {
                            State.Loading -> State.Ready(items)
                            // Selections are dropped for anything no longer listed,
                            // so a removal cannot leave the Remove button armed with
                            // folders that are already gone.
                            is State.Ready -> old.copy(
                                items = items,
                                selection = old.selection.filter { sel ->
                                    items.any { it.uri == sel }
                                },
                            )
                        }
                    }
                }
        }
    }

    /**
     * Reads one registered folder's current state.
     *
     * The entry url is what the local source addresses the folder by, so library
     * membership is a lookup of that url - the same lookup the player's
     * granted-folder resolution does, not a second notion of what is registered.
     */
    private suspend fun describe(uri: String): GrantedFolder {
        val entryUrl = localSourceFileSystem.entryUrlForGrantedFolder(uri)
        val name = runCatching { localSourceFileSystem.getEntryTitle(entryUrl) }.getOrNull().orEmpty()
        val anime = runCatching { getAnimeByUrlAndSourceId.await(entryUrl, LocalSource.ID) }.getOrNull()
        return GrantedFolder(
            uri = uri,
            name = name.ifBlank { displayPathOf(uri) },
            path = displayPathOf(uri),
            inLibrary = anime?.favorite == true,
            missing = name.isBlank(),
        )
    }

    /**
     * Something the user can recognise the folder by.
     *
     * The storage provider names a document `<volume>:<path within volume>`, so
     * the document id is already the path - it just needs the volume read as a
     * name. Falls back to the raw uri rather than inventing a path, because a
     * provider that does not use that shape is a provider this cannot read.
     */
    private fun displayPathOf(uri: String): String {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return uri
        val docId = runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
            ?: return uri
        if (!docId.contains(':')) return docId
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':')
        val volumeName = if (volume == "primary") {
            context.stringResource(AMMR.strings.am_granted_folders_internal_storage)
        } else {
            volume
        }
        return if (relative.isBlank()) volumeName else "$volumeName/$relative"
    }

    fun removeSelected() {
        viewModelScope.launchIO {
            withNonCancellableContext {
                val ready = state.value as? State.Ready ?: return@withNonCancellableContext
                val selected = ready.selection.toSet()
                if (selected.isEmpty()) return@withNonCancellableContext

                selected.forEach { uri -> release(uri) }
                storagePreferences.extraLocalFolders.set(
                    storagePreferences.extraLocalFolders.get() - selected,
                )
            }
            hideConfirmation()
        }
    }

    /**
     * Gives up the persisted grant for [uri], where there is one to give up.
     *
     * Only released when the app holds a persisted grant for that exact uri. A
     * folder registered because an ANCESTOR tree already covered it - the app's
     * own storage directory, say - has no grant of its own, and releasing the
     * ancestor's would revoke access to everything else under it.
     */
    private fun release(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == parsed }
        if (!held) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                parsed,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            logcat(LogPriority.INFO, it) { "EXTRA_LOCAL_FOLDERS could not release $uri" }
        }
    }

    fun toggleSelection(uri: String) = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(
            selection = if (uri in current.selection) {
                current.selection - uri
            } else {
                current.selection + uri
            },
        )
    }

    fun selectAll() = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(selection = current.items.fastMap { it.uri })
    }

    fun selectNotInLibrary() = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(selection = current.items.filterNot { it.inLibrary }.fastMap { it.uri })
    }

    fun invertSelection() = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(
            selection = current.items.fastMap { it.uri }.filterNot { it in current.selection },
        )
    }

    fun showConfirmation() = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(showConfirmation = true)
    }

    fun hideConfirmation() = state.update { current ->
        if (current !is State.Ready) return@update current
        current.copy(showConfirmation = false)
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<GrantedFolder>,
            val selection: List<String> = emptyList(),
            val showConfirmation: Boolean = false,
        ) : State
    }
}
// <-- AM (EXTRA_LOCAL_FOLDERS)
