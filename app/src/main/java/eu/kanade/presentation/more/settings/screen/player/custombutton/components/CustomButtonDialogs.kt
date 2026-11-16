package eu.kanade.presentation.more.settings.screen.player.custombutton.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.TextFieldValue
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlInput
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.more.settings.screen.player.editor.codeeditor.githubTheme
import eu.kanade.presentation.more.settings.screen.player.editor.codeeditor.luaHighlight
import eu.kanade.presentation.more.settings.screen.player.editor.codeeditor.toAnnotatedString
import eu.kanade.tachiyomi.ui.player.controls.components.controlButtonIconChoices
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private enum class CodeTarget {
    Tap,
    DoubleTap,
    LongPress,
    Startup,
}

@Composable
fun CustomButtonButtonDialog(
    onDismissRequest: () -> Unit,
    onAction: (String, String, String, String, String, String?, Boolean) -> Unit,
    titleRes: StringResource,
    actionRes: StringResource,
    buttonNames: List<String>,
    initialState: CustomButton?,
    onDelete: (() -> Unit)? = null,
) {
    var title by remember { mutableStateOf(initialState?.name ?: "") }
    var icon by remember { mutableStateOf(initialState?.icon ?: ControlAction.PrimaryCustomButton.key) }
    var content by remember { mutableStateOf(initialState?.content ?: "") }
    var doubleTapContent by remember { mutableStateOf(initialState?.doubleTapContent ?: "") }
    var longPressContent by remember { mutableStateOf(initialState?.longPressContent ?: "") }
    var startUp by remember { mutableStateOf(initialState?.onStartup ?: "") }
    var editingCode by remember { mutableStateOf<CodeTarget?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(text = stringResource(AYMR.strings.pref_player_custom_button_delete_question)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete?.invoke()
                    onDismissRequest()
                }) {
                    Text(text = stringResource(AYMR.strings.action_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
        return
    }

    editingCode?.let { target ->
        val code = when (target) {
            CodeTarget.Tap -> content
            CodeTarget.DoubleTap -> doubleTapContent
            CodeTarget.LongPress -> longPressContent
            CodeTarget.Startup -> startUp
        }
        CustomButtonCodeDialog(
            titleRes = target.titleRes(),
            initialCode = code,
            onDismissRequest = { editingCode = null },
            onConfirm = { updated ->
                when (target) {
                    CodeTarget.Tap -> content = updated
                    CodeTarget.DoubleTap -> doubleTapContent = updated
                    CodeTarget.LongPress -> longPressContent = updated
                    CodeTarget.Startup -> startUp = updated
                }
                editingCode = null
            },
        )
        return
    }

    val titleAlreadyExists = remember(title) { buttonNames.contains(title) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                } else {
                    Spacer(Modifier)
                }
                Row {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        enabled = title.isNotEmpty() && content.isNotEmpty() && !titleAlreadyExists,
                        onClick = {
                            onAction(
                                title,
                                content,
                                doubleTapContent,
                                longPressContent,
                                startUp,
                                icon,
                                content.isToggleButton(startUp),
                            )
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(actionRes))
                    }
                }
            }
        },
        title = {
            Row {
                Text(text = stringResource(titleRes))
                initialState?.id?.let { buttonId ->
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(AYMR.strings.pref_player_custom_button_id, buttonId),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.alpha(alpha = DISABLED_ALPHA),
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(text = stringResource(AYMR.strings.pref_player_custom_button_title)) },
                    supportingText = {
                        val msgRes = if (title.isNotEmpty() && titleAlreadyExists) {
                            AYMR.strings.pref_player_custom_button_error_exists
                        } else {
                            MR.strings.information_required_plain
                        }
                        Text(text = stringResource(msgRes))
                    },
                    isError = title.isNotEmpty() && titleAlreadyExists,
                    singleLine = true,
                )

                ButtonIconPicker(selected = icon, onSelect = { icon = it })

                ControlInput.entries.forEach { input ->
                    val code = when (input) {
                        ControlInput.Tap -> content
                        ControlInput.DoubleTap -> doubleTapContent
                        ControlInput.LongPress -> longPressContent
                    }
                    CustomButtonInputRow(
                        input = input,
                        code = code,
                        onCodeChange = { updated ->
                            when (input) {
                                ControlInput.Tap -> content = updated
                                ControlInput.DoubleTap -> doubleTapContent = updated
                                ControlInput.LongPress -> longPressContent = updated
                            }
                        },
                        onEditCode = {
                            editingCode = when (input) {
                                ControlInput.Tap -> CodeTarget.Tap
                                ControlInput.DoubleTap -> CodeTarget.DoubleTap
                                ControlInput.LongPress -> CodeTarget.LongPress
                            }
                        },
                    )
                }

                CustomButtonStartupRow(onEditCode = { editingCode = CodeTarget.Startup })
            }
        },
    )
}

@Composable
private fun ButtonIconPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedChoice = controlButtonIconChoices.firstOrNull { it.key == selected }
        ?: controlButtonIconChoices.first { it.key == ControlAction.PrimaryCustomButton.key }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            Icon(imageVector = selectedChoice.icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(AYMR.strings.pref_player_custom_button_icon),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(selectedChoice.action.stringRes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            controlButtonIconChoices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                            Icon(imageVector = choice.icon, contentDescription = null)
                            Text(text = stringResource(choice.action.stringRes))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(choice.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun CustomButtonInputRow(
    input: ControlInput,
    code: String,
    onCodeChange: (String) -> Unit,
    onEditCode: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val assignedAction = code.assignedControlAction()
    val title = when {
        assignedAction != null -> stringResource(assignedAction.stringRes)
        code.isBlank() -> stringResource(MR.strings.none)
        else -> stringResource(AYMR.strings.single_action_custom)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(input.labelRes()), style = MaterialTheme.typography.bodyMedium)
                    Text(text = title, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ControlAction.selectable
                    .filterNot { it == ControlAction.PrimaryCustomButton }
                    .forEach { action ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(action.stringRes)) },
                            onClick = {
                                expanded = false
                                onCodeChange(action.toControlActionLua())
                            },
                        )
                    }
                DropdownMenuItem(
                    text = { Text(text = stringResource(AYMR.strings.single_action_custom)) },
                    onClick = {
                        expanded = false
                        if (assignedAction != null) onCodeChange("")
                        onEditCode()
                    },
                )
            }
        }
        IconButton(onClick = onEditCode) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(MR.strings.action_edit),
            )
        }
    }
}

@Composable
private fun CustomButtonStartupRow(onEditCode: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(AYMR.strings.pref_player_custom_button_startup),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEditCode) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(MR.strings.action_edit),
            )
        }
    }
}

@Composable
private fun CustomButtonCodeDialog(
    titleRes: StringResource,
    initialCode: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val luaHighlight = remember { luaHighlight(githubTheme) }
    var code by remember {
        mutableStateOf(
            TextFieldValue(
                annotatedString = luaHighlight.toAnnotatedString(initialCode),
                composition = null,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.copy(luaHighlight.toAnnotatedString(it.text)) },
                minLines = 8,
                maxLines = 16,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code.text) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun CodeTarget.titleRes(): StringResource = when (this) {
    CodeTarget.Tap -> AYMR.strings.pref_player_custom_button_content
    CodeTarget.DoubleTap -> AYMR.strings.pref_player_custom_button_content_double
    CodeTarget.LongPress -> AYMR.strings.pref_player_custom_button_content_long
    CodeTarget.Startup -> AYMR.strings.pref_player_custom_button_startup
}

private fun ControlInput.labelRes(): StringResource = when (this) {
    ControlInput.Tap -> AYMR.strings.pref_player_controls_tap
    ControlInput.DoubleTap -> AYMR.strings.pref_player_controls_double_tap
    ControlInput.LongPress -> AYMR.strings.pref_player_controls_long_press
}

private val controlActionLuaPattern = Regex(
    pattern = """\s*aniyomi\.control_action\(\s*["']([a-z_]+)["']\s*\)\s*""",
)

private fun String.isToggleButton(onStartup: String): Boolean =
    contains("aniyomi.set_button_toggle") || onStartup.contains("aniyomi.set_button_toggle")

private fun String.assignedControlAction(): ControlAction? = controlActionLuaPattern.matchEntire(this)
    ?.groupValues
    ?.getOrNull(1)
    ?.let(ControlAction::fromKey)

private fun ControlAction.toControlActionLua(): String = if (this == ControlAction.None) {
    ""
} else {
    "aniyomi.control_action(\"$key\")"
}

@Composable
fun CustomButtonCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String, String, String, String, String, String?, Boolean) -> Unit,
    buttonNames: List<String>,
) {
    CustomButtonButtonDialog(
        onDismissRequest = onDismissRequest,
        onAction = onCreate,
        titleRes = AYMR.strings.pref_player_custom_button_add,
        actionRes = MR.strings.action_add,
        buttonNames = buttonNames,
        initialState = null,
    )
}

@Composable
fun CustomButtonEditDialog(
    onDismissRequest: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onEdit: (String, String, String, String, String, String?, Boolean) -> Unit,
    buttonNames: List<String>,
    initialState: CustomButton,
) {
    CustomButtonButtonDialog(
        onDismissRequest = onDismissRequest,
        onAction = onEdit,
        titleRes = AYMR.strings.pref_player_custom_button_edit,
        actionRes = MR.strings.action_edit,
        buttonNames = buttonNames,
        initialState = initialState,
        onDelete = onDelete,
    )
}

@Composable
fun CustomButtonDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    buttonTitle: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(AYMR.strings.pref_player_custom_button_delete)) },
        text = {
            Text(text = stringResource(AYMR.strings.pref_player_custom_button_delete_confirm, buttonTitle))
        },
    )
}
