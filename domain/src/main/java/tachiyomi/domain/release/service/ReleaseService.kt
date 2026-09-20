package tachiyomi.domain.release.service

import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

interface ReleaseService {

    suspend fun latest(arguments: GetApplicationRelease.Arguments): Release?

    // AM (WHATS_NEW) -->

    /**
     * Returns every published release visible to this build, newest first. Unlike [latest] this
     * hits the list endpoint, so a user who skipped versions can be shown the notes they missed.
     */
    suspend fun releases(
        arguments: GetApplicationRelease.Arguments,
        page: Int = 1,
        requireDownloadLink: Boolean = true,
    ): List<Release>
    // <-- AM (WHATS_NEW)
}
