package eu.kanade.tachiyomi.animesource.online

import android.app.Application
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// AY -->
// Scope for skipped-item toasts
private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
// <-- AY

/**
 * A simple implementation for sources from a website using Jsoup, an HTML parser.
 */
@Suppress("unused")
@Deprecated(
    message = "In most cases sources only require a subset of the methods from this class. " +
        "Source developers should make their own implementation according to their needs.",
)
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {

    // AY -->
    // Isolates bad element; logs+skips
    private fun <T> parseElementOrNull(
        element: Element,
        selectorName: String,
        skipped: MutableList<SkippedItem>,
        block: (Element) -> T,
    ): T? {
        return try {
            block(element)
        } catch (e: Exception) {
            val id = bestEffortId(element)
            val reason = classifyReason(e)
            logcat(LogPriority.ERROR, e) {
                "$name: $selectorName crashed on element (id=$id, reason=$reason), skipping just that entry"
            }
            skipped += SkippedItem(id, reason)
            null
        }
    }

    private fun bestEffortId(element: Element): String {
        // The element never became an SAnime, so there's no url/title to report - best
        // effort is whatever raw signal the markup itself offers, most to least useful.
        element.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }
        element.text().takeIf { it.isNotBlank() }?.let { return it.take(40) }
        return "unidentified element"
    }

    private fun classifyReason(e: Exception): String {
        return when (e) {
            is NullPointerException, is NoSuchElementException -> "expected element not found"
            is IndexOutOfBoundsException -> "expected element not found"
            is NumberFormatException -> "unexpected/malformed value"
            else -> "parsing error (${e::class.simpleName})"
        }
    }

    private fun reportSkipped(skipped: List<SkippedItem>) {
        if (skipped.isEmpty()) return
        val context = Injekt.get<Application>()
        toastScope.launch {
            val message = if (skipped.size == 1) {
                val item = skipped[0]
                "$name: skipped 1 item (${item.id}): ${item.reason}"
            } else {
                "$name: skipped ${skipped.size} items on this page (${skipped.first().reason}, " +
                    "others - see logs for details)"
            }
            context.toast(message)
        }
    }

    private data class SkippedItem(val id: String, val reason: String)
    // <-- AY

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // AY -->
        val skipped = mutableListOf<SkippedItem>()
        val animes = document.select(popularAnimeSelector()).mapNotNull { element ->
            parseElementOrNull(element, "popularAnimeFromElement", skipped) { popularAnimeFromElement(it) }
        }
        reportSkipped(skipped)
        // <-- AY

        val hasNextPage = popularAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun popularAnimeSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [popularAnimeSelector].
     */
    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun popularAnimeNextPageSelector(): String?

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // AY -->
        val skipped = mutableListOf<SkippedItem>()
        val animes = document.select(searchAnimeSelector()).mapNotNull { element ->
            parseElementOrNull(element, "searchAnimeFromElement", skipped) { searchAnimeFromElement(it) }
        }
        reportSkipped(skipped)
        // <-- AY

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun searchAnimeSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [searchAnimeSelector].
     */
    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun searchAnimeNextPageSelector(): String?

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // AY -->
        val skipped = mutableListOf<SkippedItem>()
        val animes = document.select(latestUpdatesSelector()).mapNotNull { element ->
            parseElementOrNull(element, "latestUpdatesFromElement", skipped) { latestUpdatesFromElement(it) }
        }
        reportSkipped(skipped)
        // <-- AY

        val hasNextPage = latestUpdatesNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun latestUpdatesSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [latestUpdatesSelector].
     */
    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun latestUpdatesNextPageSelector(): String?

    /**
     * Parses the response from the site and returns the details of an anime.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun animeDetailsParse(response: Response): SAnime {
        return animeDetailsParse(response.asJsoup())
    }

    /**
     * Returns the details of the anime from the given [document].
     *
     * @param document the parsed document.
     */
    protected abstract fun animeDetailsParse(document: Document): SAnime

    /**
     * Parses the response from the site and returns a list of episodes.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each episode.
     */
    protected abstract fun episodeListSelector(): String

    /**
     * Returns an episode from the given element.
     *
     * @param element an element obtained from [episodeListSelector].
     */
    protected abstract fun episodeFromElement(element: Element): SEpisode

    /**
     * Parses the response from the site and returns a list of seasons.
     *
     * @since extensions-lib 16
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun seasonListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select(seasonListSelector()).map { seasonFromElement(it) }
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each season.
     *
     * @since extensions-lib 16
     */
    protected abstract fun seasonListSelector(): String

    /**
     * Returns a season from the given element.
     *
     * @since extensions-lib 16
     * @param element an element obtained from [seasonListSelector].
     */
    protected abstract fun seasonFromElement(element: Element): SAnime

    /**
     * Parses the response from the site and returns the hoster list.
     *
     * @since extensions-lib 16
     * @param response the response from the site.
     * @return the list of hosters.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        return document.select(hosterListSelector()).map(::hosterFromElement)
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each hoster.
     *
     * @since extensions-lib 16
     */
    protected abstract fun hosterListSelector(): String

    /**
     * Returns a hoster from the given element.
     *
     * @since extensions-lib 16
     * @param element an element obtained from [hosterListSelector].
     */
    protected abstract fun hosterFromElement(element: Element): Hoster

    /**
     * Parses the response from the site and returns the page list.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).map { videoFromElement(it) }
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each video.
     */
    protected abstract fun videoListSelector(): String

    /**
     * Returns a video from the given element.
     *
     * @param element an element obtained from [videoListSelector].
     */
    protected abstract fun videoFromElement(element: Element): Video

    /**
     * Parse the response from the site and returns the absolute url to the source video.
     *
     * @param response the response from the site.
     */
    @Deprecated(
        "The helper functions are inherently limiting and hides the underlying implementation. Source developers should make their own implementation according to their needs.",
    )
    override fun videoUrlParse(response: Response): String {
        return videoUrlParse(response.asJsoup())
    }

    /**
     * Returns the absolute url to the source image from the document.
     *
     * @param document the parsed document.
     */
    protected abstract fun videoUrlParse(document: Document): String
}
