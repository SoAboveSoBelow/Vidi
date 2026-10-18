package tachiyomi.domain.storage.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

@Inject
@SingleIn(AppScope::class)
class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    // AM (EXTRA_LOCAL_FOLDERS) -->
    /**
     * Folders outside [baseStorageDirectory] whose contents the local source also
     * covers, as persisted SAF tree URIs.
     *
     * These come from a user granting a folder - see PlayerViewModel's folder
     * grant for an externally-opened video. A granted folder's videos become
     * ordinary local source entries, so the episode list, next/previous, resume
     * position and history are the existing ones rather than anything parallel.
     *
     * The grant itself is persisted with the system (takePersistableUriPermission);
     * this is the record of which granted folders the local source should scan.
     */
    val extraLocalFolders: Preference<Set<String>> = preferenceStore.getStringSet(
        "pref_extra_local_folders",
        emptySet(),
    )
    // <-- AM (EXTRA_LOCAL_FOLDERS)

    // AM (FILE_SIZE) -->
    val showEpisodeFileSize: Preference<Boolean> = preferenceStore.getBoolean("pref_show_downloaded_episode_size", true)
    // <-- AM (FILE_SIZE)
}
