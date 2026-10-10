package aniyomi.source.js

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 6 - does a project work through the app's own source interface?
 *
 * Everything so far called the runtime directly. This goes through
 * [JsProjectSource], which is an [eu.kanade.tachiyomi.animesource.AnimeCatalogueSource],
 * so the whole path the app uses is exercised: JSON out of JavaScript, decoded
 * into `SAnime`/`SEpisode`, with the host owning identity.
 *
 * Deliberately not registered with the source manager. That is a separate change
 * with its own risks - library binding, ids, draft gating - and there is no point
 * taking them before the adapter itself is known to work.
 *
 * Five calls against the live site: popular, search, details and episodes, plus
 * one re-used entry to confirm the runtime loads once and stays warm.
 */
class JsAdapterSpike(private val network: NetworkHelper) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private suspend fun measure(): String {
        val source = JsProjectSource(
            id = HOST_ASSIGNED_ID,
            name = "Rule34Video (project)",
            lang = "en",
            supportsLatest = true,
            script = PROJECT_SCRIPT,
            baseUrl = BASE_URL,
            networkHelper = network,
        )

        return try {
            buildString {
                appendLine("JsAdapterSpike: running a project through AnimeCatalogueSource")
                appendLine()

                val popular = timed("getPopularAnime(1)") { source.getPopularAnime(1) }
                appendLine(describePage("popular", popular.value, popular.millis))
                val first = popular.value.animes.firstOrNull()

                val latest = timed("getLatestUpdates(1)") { source.getLatestUpdates(1) }
                appendLine(describePage("latest", latest.value, latest.millis))

                val search = timed("getSearchAnime") {
                    source.getSearchAnime(1, SEARCH_QUERY, AnimeFilterList())
                }
                appendLine(describePage("search '$SEARCH_QUERY'", search.value, search.millis))

                if (first == null) {
                    appendLine("no entries, so details and episodes were skipped")
                } else {
                    appendLine()
                    appendLine("first entry from popular: ${first.title}")
                    appendLine("  url=${first.url}")
                    appendLine("  thumbnail=${first.thumbnail_url}")

                    val details = timed("getAnimeDetails") { source.getAnimeDetails(first) }
                    appendLine(describeDetails(details.value, details.millis))

                    val episodes = timed("getEpisodeList") { source.getEpisodeList(first) }
                    appendLine(
                        "episodes: ${episodes.value.size} in ${fmt(episodes.millis)} ms" +
                            (
                                episodes.value.firstOrNull()
                                    ?.let { " - first '${it.name}' number=${it.episode_number} url=${it.url}" }
                                    ?: ""
                                ),
                    )
                }

                appendLine()
                appendLine("requests: ${source.requestCount()}, bytes: ${source.bytesRead()}")
                appendLine("source id: ${source.id} (host-assigned, not derived from the project)")
                appendLine("toString: $source")
            }
        } catch (e: Throwable) {
            "JsAdapterSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(14).joinToString("\n")
        } finally {
            source.close()
        }
    }

    private fun describePage(label: String, page: AnimesPage, millis: Double): String {
        val withThumbnail = page.animes.count { !it.thumbnail_url.isNullOrEmpty() }
        val withTitle = page.animes.count { it.title.isNotEmpty() }
        return "$label: ${page.animes.size} entries in ${fmt(millis)} ms, " +
            "hasNextPage=${page.hasNextPage}, $withTitle titled, $withThumbnail with thumbnails"
    }

    private fun describeDetails(anime: SAnime, millis: Double): String = buildString {
        appendLine("details in ${fmt(millis)} ms:")
        appendLine("  title=${anime.title}")
        appendLine("  status=${anime.status} initialized=${anime.initialized}")
        appendLine("  genre=${anime.genre?.take(GENRE_PREVIEW)}")
        append("  description=${anime.description?.take(DESCRIPTION_PREVIEW)?.replace('\n', ' ')}")
    }

    private class Timed<T>(val value: T, val millis: Double)

    private suspend fun <T> timed(label: String, block: suspend () -> T): Timed<T> {
        val start = System.nanoTime()
        return try {
            Timed(block(), (System.nanoTime() - start) / NANOS)
        } catch (e: Throwable) {
            throw IllegalStateException("$label failed: ${e.message}", e)
        }
    }

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private companion object {
        const val NANOS = 1_000_000.0
        const val BASE_URL = "https://rule34video.com"
        const val SEARCH_QUERY = "parasite black"
        const val GENRE_PREVIEW = 120
        const val DESCRIPTION_PREVIEW = 160

        /**
         * A host-assigned id. Arbitrary here, and deliberately outside the range
         * `AnimeHttpSource.generateId` produces so a spike run can never collide
         * with a real extension's library entries.
         */
        const val HOST_ASSIGNED_ID = -9_000_000_000_000_001L

        /**
         * The full catalogue contract, written as a project.
         *
         * `details` reads OpenGraph rather than a styled heading - that metadata
         * exists for crawlers, so it survives the redesigns that broke the
         * compiled extension twice tonight. `rawIn` is used instead of `attrIn`
         * because a `content` attribute must not be url-resolved.
         */
        val PROJECT_SCRIPT = """
            var source = {
                meta: {
                    apiVersion: 1,
                    name: 'Rule34Video (project)',
                    lang: 'en',
                    baseUrl: 'https://rule34video.com',
                    nsfw: true
                },

                _headers: function () {
                    return { 'Referer': this.meta.baseUrl + '/' };
                },

                _listing: function (url) {
                    var doc = http.get(url, this._headers()).html();
                    var cards = doc.select('div.item.thumb');
                    var entries = [];

                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var link = card.selectFirst('a.th');
                        if (link === null) continue;

                        entries.push({
                            url: link.absAttr('href'),
                            title: card.textIn('div.thumb_title') || 'Untitled',
                            thumbnail: card.attrIn('div.img img', 'data-rd-src', 'data-rd-jpg', 'src')
                        });
                    }

                    return {
                        entries: entries,
                        hasNextPage: doc.selectFirst('div.item.pager.next a') !== null
                    };
                },

                popular: function (args) {
                    return this._listing(this.meta.baseUrl + '/latest-updates/' + (args.page || 1) + '/');
                },

                latest: function (args) {
                    return this._listing(this.meta.baseUrl + '/latest-updates/' + (args.page || 1) + '/');
                },

                search: function (args) {
                    var slug = String(args.query || '').replace(/\s+/g, '-');
                    return this._listing(this.meta.baseUrl + '/search/' + slug + '/');
                },

                details: function (args) {
                    var doc = http.get(args.url, this._headers()).html();
                    var tags = doc.select('a.tag_item:not(.tag_item_download)');
                    var genre = [];
                    for (var i = 0; i < tags.length && i < 20; i++) {
                        var text = tags[i].text();
                        if (text && text.indexOf('Suggest') === -1) genre.push(text);
                    }

                    return {
                        url: args.url,
                        title: doc.rawIn('meta[property="og:title"]', 'content') ||
                            doc.textIn('h1') || 'Untitled',
                        thumbnail: doc.rawIn('meta[property="og:image"]', 'content'),
                        description: doc.textIn('div.js-vp-desc') ||
                            doc.rawIn('meta[property="og:description"]', 'content'),
                        genre: genre,
                        status: 'completed'
                    };
                },

                episodes: function (args) {
                    return [{ url: args.url, name: 'Video', number: 1 }];
                }
            };
        """.trimIndent()
    }
}
