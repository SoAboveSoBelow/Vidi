package tachiyomi.data.release

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        return release.toRelease(isFoss = arguments.isFoss)
    }

    // AM (WHATS_NEW) -->
    override suspend fun releases(
        arguments: GetApplicationRelease.Arguments,
        page: Int,
        requireDownloadLink: Boolean,
    ): List<Release> {
        val url = "https://api.github.com/repos/${arguments.repository}/releases" +
            "?per_page=$PAGE_SIZE&page=$page"
        val releases = with(json) {
            networkService.client
                .newCall(GET(url))
                .awaitSuccess()
                .parseAs<List<GithubRelease>>()
        }

        return releases.mapNotNull {
            it.toRelease(isFoss = arguments.isFoss, requireDownloadLink = requireDownloadLink)
        }
    }

    /**
     * Maps a GitHub release onto the domain model, normalising the body into markdown that
     * renders sanely in-app. Returns null when no asset matches this device's ABI, since a
     * release we cannot offer is not a release we should surface.
     */
    private fun GithubRelease.toRelease(isFoss: Boolean, requireDownloadLink: Boolean = true): Release? {
        val downloadLink = getDownloadLink(release = this, isFoss = isFoss)
        // The update path must have something to install, but the history browser should still
        // list old releases whose asset naming predates the current ABI split.
        if (downloadLink == null && requireDownloadLink) return null

        return Release(
            version = version,
            info = info.normalizeReleaseBody(),
            releaseLink = releaseLink,
            downloadLink = downloadLink.orEmpty(),
            preRelease = preRelease,
            draft = draft,
        )
    }

    /**
     * GitHub bodies carry a few constructs that render badly as plain markdown: bare @mentions,
     * download-count badge images, and auto-generated "owner/repo@from...to" compare text that is
     * linkified by GitHub's own renderer but not by ours.
     */
    private fun String.normalizeReleaseBody(): String {
        return this
            .substringBeforeLast(HIDDEN_BODY_MARKER)
            .replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            }
            .replace(gitHubDownloadBadgeRegex, "")
            .replace(gitHubCompareRegex) { match ->
                val owner = match.groups["owner"]!!.value
                val repo = match.groups["repo"]!!.value
                val from = match.groups["from"]!!.value
                val to = match.groups["to"]!!.value
                "[$owner/$repo@$from...$to](https://github.com/$owner/$repo/compare/$from...$to)"
            }
            .trim()
    }
    // <-- AM (WHATS_NEW)

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? {
        val map = release.assets.associate { asset ->
            BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink
        }

        return if (!isFoss) {
            map[Build.SUPPORTED_ABIS[0]] ?: map[null]
        } else {
            map[FOSS]
        }
    }

    companion object {
        // AM (RELEASE_HISTORY) -->
        /** GitHub's list endpoint default; also what the history screen pages by. */
        const val PAGE_SIZE = 30
        // <-- AM (RELEASE_HISTORY)

        private const val FOSS = "foss"
        private val BUILD_TYPES = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)

        // AM (WHATS_NEW) -->

        /** Everything after this marker is release plumbing, not user-facing notes. */
        private const val HIDDEN_BODY_MARKER = "<!-->"

        /** Shields-style download badge images, which have no meaning in an in-app changelog. */
        private val gitHubDownloadBadgeRegex =
            """!\[[^\]]*\]\(https://img\.shields\.io/[^)]*\)""".toRegex()

        /** GitHub's auto-generated compare text, e.g. "owner/repo@v1.0.0...v1.1.0". */
        private val gitHubCompareRegex =
            """(?<owner>[\w.-]+)/(?<repo>[\w.-]+)@(?<from>[\w.-]+)\.\.\.(?<to>[\w.-]+)""".toRegex()
        // <-- AM (WHATS_NEW)
    }
}
