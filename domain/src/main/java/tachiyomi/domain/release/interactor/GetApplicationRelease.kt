package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isNightly,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isNightly) {
            // Nightly builds are tagged as something like "r1234"
            newVersion.toIntOrNull()?.let { it > commitCount } ?: false
        } else {
            // Release builds are tagged as something like "v0.1.2".
            // Drop any versionNameSuffix ("0.19.13-1234") before stripping non-digits,
            // otherwise the suffix is concatenated onto the last component.
            val oldVersion = versionName.substringBefore("-").replace("[^\\d.]".toRegex(), "")

            compareSemVer(parseSemVer(newVersion), parseSemVer(oldVersion)) > 0
        }
    }

    private fun parseSemVer(version: String): List<Int> {
        return version.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Compares two dot-separated version component lists left to right, treating a missing
     * component as 0 so that lists of differing length compare correctly ("0.19.13" vs
     * "0.19.13.1"). Returns a negative number, zero, or a positive number as [a] is less
     * than, equal to, or greater than [b].
     */
    private fun compareSemVer(a: List<Int>, b: List<Int>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0

        repeat(maxOf(a.size, b.size)) { index ->
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }

        return 0
    }

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
