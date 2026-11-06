// AM (LOCAL_SOURCE_APP_FILTER) -->
package tachiyomi.source.local.filter

import android.content.Context
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.animiru.AMMR

/**
 * Narrows local source browse to one app group - a top-level directory that
 * holds only subdirectories, which is what a video saved from an app lands in
 * (`<root>/Chrome/<site>/<file>`; see DownloadDestination.LocalSource).
 *
 * A group is never itself an entry in browse - `Chrome` holds no video of its
 * own, and the sites inside it are what the list shows, grouped or not. This
 * filter only narrows which of those sites are listed.
 *
 * The first option is "All" and the second is "Local" - the ordinary entries,
 * those holding video files directly, which belong to no app. Every option
 * after those is one app group, in the order [groups] was built.
 *
 * The list is rebuilt each time the filter list is requested, since it is a
 * reading of what is on disk right now and a group stops existing the moment
 * its directory does.
 */
class AppGroup(context: Context, private val groups: List<Group>) : AnimeFilter.Select<String>(
    context.stringResource(AMMR.strings.am_local_filter_app),
    buildList {
        add(context.stringResource(AMMR.strings.am_local_filter_app_all))
        add(context.stringResource(AMMR.strings.am_local_filter_app_local))
        addAll(groups.map { it.title })
    }.toTypedArray(),
) {

    /**
     * One selectable group: addressed by [url], shown as [title].
     *
     * The two differ for a granted folder, whose url is a percent-encoded tree
     * uri that is a provider path and not something to show anyone - the same
     * split LocalSourceFileSystem.getEntryTitle makes.
     */
    data class Group(val url: String, val title: String)

    /**
     * The url of the group this filter is restricted to, or null for both
     * "All" and "Local" - neither of which names a group. The caller tells
     * those two apart with [isLocalOnly].
     */
    val selectedGroup: String?
        get() = groups.getOrNull(state - FIRST_GROUP_INDEX)?.url

    /** Whether the selection is the no-app "Local" option. */
    val isLocalOnly: Boolean
        get() = state == LOCAL_INDEX

    companion object {
        private const val LOCAL_INDEX = 1
        private const val FIRST_GROUP_INDEX = 2
    }
}
// <-- AM (LOCAL_SOURCE_APP_FILTER)
