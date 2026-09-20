package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.anime.components.MarkdownRender
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

/**
 * The release-notes page, shared by the post-update What's new flow and the release history
 * detail. The only difference between the two is where the state comes from.
 */
@Composable
fun ReleaseNotesPage(
    headingText: String,
    subtitleText: String,
    state: ReleaseNotesUiState,
    onOpenInBrowser: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    InfoScreen(
        icon = Icons.Outlined.NewReleases,
        headingText = headingText,
        subtitleText = subtitleText,
        acceptText = when (state) {
            is ReleaseNotesUiState.Error -> stringResource(MR.strings.action_retry)
            else -> stringResource(MR.strings.action_ok)
        },
        onAcceptClick = when (state) {
            is ReleaseNotesUiState.Error -> onRetry
            else -> onDismiss
        },
        canAccept = state !is ReleaseNotesUiState.Loading,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            when (state) {
                is ReleaseNotesUiState.Loading -> {
                    CircularProgressIndicator()
                }

                is ReleaseNotesUiState.Error -> {
                    Text(
                        text = stringResource(AMMR.strings.whats_new_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                is ReleaseNotesUiState.Empty -> {
                    Text(
                        text = stringResource(AMMR.strings.whats_new_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                is ReleaseNotesUiState.Loaded -> {
                    MarkdownRender(
                        content = state.changelogInfo,
                        modifier = Modifier.fillMaxWidth(),
                        flavour = remember { GFMFlavourDescriptor() },
                    )
                }
            }

            TextButton(onClick = onOpenInBrowser) {
                Text(text = stringResource(MR.strings.update_check_open))
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun ReleaseNotesPagePreview() {
    TachiyomiPreviewTheme {
        ReleaseNotesPage(
            headingText = "What's new",
            subtitleText = "Updated to v0.19.15",
            state = ReleaseNotesUiState.Loaded(
                changelogInfo = "- Dummy PiP: gesture, bounds, rotation, and rendering fixes",
                latestVersion = "v0.19.15",
                releaseLink = "https://github.com/SoAboveSoBelow/Vidi/releases",
            ),
            onOpenInBrowser = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}
