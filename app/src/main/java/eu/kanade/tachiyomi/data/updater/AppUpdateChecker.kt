package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isNightlyBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            result
        }
    }

    // AM (WHATS_NEW) -->

    /**
     * Release notes for every version between [sinceVersion] (exclusive) and the installed build
     * (inclusive). Shares the Arguments assembly with [checkForUpdate] so the repo, channel and
     * ABI handling cannot drift between the two paths.
     */
    suspend fun getReleaseNotes(sinceVersion: String?): List<Release> {
        return withIOContext {
            getApplicationRelease.awaitReleaseNotes(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isNightlyBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                ),
                sinceVersion = sinceVersion,
            )
        }
    }
    // <-- AM (WHATS_NEW)

    // AM (RELEASE_HISTORY) -->

    /** A page of the full release history, newest first. */
    suspend fun getReleaseHistory(page: Int): List<Release> {
        return withIOContext {
            getApplicationRelease.awaitReleaseHistory(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isNightlyBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                ),
                page = page,
            )
        }
    }
    // <-- AM (RELEASE_HISTORY)
}

val GITHUB_REPO: String by lazy {
    // AM (UPDATER_REPO_FIX) -->
    // Was quickdesh/Animiru(-preview) - pointed the in-app updater at upstream's releases
    // instead of this fork's, so it would prompt users to install an upstream Animiru APK.
    // No SoAboveSoBelow/Vidi-preview repo exists, so both branches point at the main repo.
    "SoAboveSoBelow/Vidi"
    // <-- AM (UPDATER_REPO_FIX)
}

val RELEASE_TAG: String by lazy {
    if (isNightlyBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
