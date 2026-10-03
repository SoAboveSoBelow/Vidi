package mihon.feature.migration.list.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MigrationProgressDialog(
    progress: Float,
    exitMigration: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(onClick = exitMigration) {
                Text(text = stringResource(MR.strings.migrationListScreen_progressDialog_cancelLabel))
            }
        },
        text = {
            if (!progress.isNaN()) {
                val progressAnimated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "migration_progress",
                )
                LinearProgressIndicator(
                    progress = { progressAnimated },
                )
            }
        },
    )
}
