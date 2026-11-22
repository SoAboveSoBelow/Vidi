package eu.kanade.presentation.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.i18n.stringResource

// AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
/**
 * The history list's filters.
 *
 * A sheet, like the library's and the Updates tab's beside it, rather than the
 * dropdown this started as. A dropdown can hold a row of toggles and nothing
 * else: the moment one filter needed a VALUE, its choices had to go somewhere,
 * and both places they could go were wrong - eleven radio rows in the menu
 * turned two toggles into a wall, and a dialog opened from a menu entry made
 * one filter take two taps more than the others. A sheet holds all three at
 * once, and the app already has the component for it.
 *
 * The threshold inside it is a dropdown - [SelectItem], the one the storage
 * screen uses - because that is what the app already offers for "one value out
 * of a short list". It has been a row of radio buttons, a dialog of radio
 * buttons and a row of chips, and all three were the same mistake: eleven
 * options laid out on the page, however they were drawn.
 */
@Composable
fun HistoryFilterDialog(
    state: HistoryViewModel.State,
    onToggleWatchedOnly: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onMinSecondsSeenChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(stringResource(MR.strings.action_filter)),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            FilterSheet(
                state = state,
                onToggleWatchedOnly = onToggleWatchedOnly,
                onToggleDownloadedOnly = onToggleDownloadedOnly,
                onMinSecondsSeenChange = onMinSecondsSeenChange,
            )
        }
    }
}

@Composable
private fun ColumnScope.FilterSheet(
    state: HistoryViewModel.State,
    onToggleWatchedOnly: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onMinSecondsSeenChange: (Int) -> Unit,
) {
    // Checkboxes rather than the tri-state items the Updates sheet uses: these
    // two have no third meaning. There is no "only entries that are NOT
    // downloaded" worth offering on a list of things already watched.
    CheckboxItem(
        label = stringResource(AMMR.strings.am_history_filter_watched),
        checked = state.watchedOnly,
        onClick = onToggleWatchedOnly,
    )
    CheckboxItem(
        label = stringResource(AMMR.strings.am_history_filter_downloaded),
        checked = state.downloadedOnly,
        onClick = onToggleDownloadedOnly,
    )

    // Off is the first option rather than a switch beside the dropdown, so the
    // row reads as one setting with one answer in it.
    //
    // Resolved to strings up here rather than in SelectItem's toString, which
    // is an ordinary lambda and cannot call stringResource.
    val labels = ArrayList<String>(MIN_SECONDS_SEEN_CHOICES.size)
    for (seconds in MIN_SECONDS_SEEN_CHOICES) {
        labels += if (seconds == 0) {
            stringResource(AMMR.strings.am_history_min_seconds_off)
        } else {
            stringResource(AMMR.strings.am_history_min_seconds_choice, seconds)
        }
    }
    SelectItem(
        label = stringResource(AMMR.strings.am_pref_history_min_seconds),
        options = labels.toTypedArray(),
        selectedIndex = MIN_SECONDS_SEEN_CHOICES.indexOf(state.minSecondsSeen).coerceAtLeast(0),
        onSelect = { onMinSecondsSeenChange(MIN_SECONDS_SEEN_CHOICES[it]) },
    )
}

/**
 * The thresholds offered, in seconds, with 0 for off.
 *
 * A short list of whole seconds rather than a free number: what this separates
 * is something opened and abandoned from something actually watched, and that
 * boundary is a few seconds wide. A value beyond ten would start hiding
 * episodes genuinely sampled, which is not what the filter is for.
 */
private val MIN_SECONDS_SEEN_CHOICES = (0..10).toList().toTypedArray()
// <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
