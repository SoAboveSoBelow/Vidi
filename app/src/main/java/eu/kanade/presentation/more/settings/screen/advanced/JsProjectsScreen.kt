// AM (JS_PROJECTS) -->
package eu.kanade.presentation.more.settings.screen.advanced

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import aniyomi.source.js.JS_PROJECT_TEMPLATE
import aniyomi.source.js.JsProjectEntry
import aniyomi.source.js.JsProjectManifest
import aniyomi.source.js.freeSlug
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.FloatingActionAddButton
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen

/**
 * The projects on this device.
 *
 * Shows drafts and projects whose manifest does not parse alongside working
 * ones, with the reason attached - see [JsProjectEntry]. A list that quietly
 * omitted broken work would make it unrepairable, since this screen is the only
 * way back into it.
 */
class JsProjectsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val repository = remember { context.appGraph.jsProjectRepository }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        val entries by repository.entries.collectAsState()
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<String?>(null) }
        var importError by remember { mutableStateOf<String?>(null) }

        /*
         * A document picker rather than a paste. A project is a couple of
         * hundred lines, and the clipboard is not a reliable way to move that
         * much text on a phone - some keyboards and clipboard managers truncate
         * long content silently, which looks exactly like a project that half
         * works. The file arrives whole or not at all.
         */
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val outcome = withIOContext {
                    runCatching {
                        val script = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("could not open the file")

                        val name = context.displayNameOf(uri)
                            ?.substringBeforeLast('.')
                            ?.takeIf { it.isNotBlank() }
                            ?: "Imported project"

                        val slug = repository.store.freeSlug(name)
                        repository.store.save(
                            slug = slug,
                            manifest = JsProjectManifest(name = name),
                            script = script,
                        )
                        repository.refresh()
                        slug
                    }
                }
                outcome
                    .onSuccess { navigator.push(JsProjectEditorScreen(it)) }
                    .onFailure { importError = it.message ?: it::class.simpleName }
            }
        }

        LaunchedEffect(Unit) { withIOContext { repository.refresh() } }

        if (creating) {
            CreateProjectDialog(
                onDismissRequest = { creating = false },
                onCreate = { name ->
                    creating = false
                    scope.launch {
                        val slug = withIOContext {
                            val slug = repository.store.freeSlug(name)
                            repository.store.save(
                                slug = slug,
                                manifest = JsProjectManifest(name = name.trim().ifEmpty { slug }),
                                script = JS_PROJECT_TEMPLATE,
                            )
                            repository.refresh()
                            slug
                        }
                        navigator.push(JsProjectEditorScreen(slug))
                    }
                },
            )
        }

        importError?.let { message ->
            AlertDialog(
                onDismissRequest = { importError = null },
                title = { Text(text = "Could not import") },
                text = { Text(text = message) },
                confirmButton = {
                    TextButton(onClick = { importError = null }) { Text(text = "OK") }
                },
            )
        }

        deleting?.let { slug ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(text = "Delete '$slug'?") },
                text = {
                    Text(
                        text = "The project, its code and its revision history are removed from " +
                            "this device. Library entries from it are left alone, and will show " +
                            "as an unknown source.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleting = null
                            scope.launch {
                                withIOContext {
                                    repository.store.delete(slug)
                                    repository.refresh()
                                }
                            }
                        },
                    ) {
                        Text(text = "Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(text = "Cancel") }
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "JavaScript projects",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = "Import from file",
                                    icon = Icons.Outlined.FileOpen,
                                    // Any text type: a project is a .js file,
                                    // but providers disagree about whether that
                                    // is text/javascript, application/javascript
                                    // or just text/plain.
                                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                FloatingActionAddButton(
                    lazyListState = listState,
                    onClick = { creating = true },
                )
            },
        ) { contentPadding ->
            if (entries.isEmpty()) {
                EmptyScreen(
                    message = "No projects yet. A project is your own code, run by the app as a source.",
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyColumn(state = listState, contentPadding = contentPadding) {
                    items(entries, key = { it.slug }) { entry ->
                        ProjectRow(
                            entry = entry,
                            onClick = { navigator.push(JsProjectEditorScreen(entry.slug)) },
                            onDelete = { deleting = entry.slug },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProjectRow(
        entry: JsProjectEntry,
        onClick: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                modifier = Modifier.fillMaxWidth(FILL_BESIDE_ACTION),
            ) {
                when (entry) {
                    is JsProjectEntry.Readable -> {
                        Text(
                            text = entry.project.manifest.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = buildString {
                                append(entry.slug)
                                append(" · ")
                                append(entry.project.manifest.lang.uppercase())
                                append(" · ")
                                append(if (entry.project.manifest.active) "active" else "draft")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is JsProjectEntry.Unreadable -> {
                        Text(text = entry.slug, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = entry.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            TextButton(onClick = onDelete, modifier = Modifier.padding(start = MaterialTheme.padding.small)) {
                Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }

    @Composable
    private fun CreateProjectDialog(
        onDismissRequest: () -> Unit,
        onCreate: (String) -> Unit,
    ) {
        var name by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = "New project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "Name") },
                    singleLine = true,
                    // The name is a title, not code; the directory name is
                    // derived from it once and never again.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onCreate(name) },
                    enabled = name.isNotBlank(),
                ) {
                    Text(text = "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(text = "Cancel") }
            },
        )
    }

    /**
     * The picked file's name, for naming the project after it.
     *
     * A content uri has no path worth reading, so the provider has to be asked;
     * when it declines, the caller falls back to a generic name rather than
     * showing the uri.
     */
    private fun Context.displayNameOf(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private companion object {
        /** Leaves the delete button its own room without a fixed width. */
        const val FILL_BESIDE_ACTION = 0.8f
    }
}
// <-- AM (JS_PROJECTS)
