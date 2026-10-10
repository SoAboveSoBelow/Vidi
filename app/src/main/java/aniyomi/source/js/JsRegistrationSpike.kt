package aniyomi.source.js

import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager

/**
 * Spike 8 - is a project actually a source as far as the app is concerned?
 *
 * The first phase that touches shared state, so it is written to leave nothing
 * behind. It saves a project, refreshes the repository, waits for
 * [SourceManager] to pick it up, browses through the manager's own handle rather
 * than its own reference, then deletes it and waits for it to disappear again.
 *
 *  A. the manager does not know the id yet
 *  B. after save + refresh, `get(id)` returns a JsProjectSource
 *  C. it is in `getAll()` and absent from `getOnlineSources()` - projects are not
 *     AnimeHttpSource, same as LocalSource, which is why the local-aware UI paths
 *     are the ones that will handle them
 *  D. browsing through the manager's instance works
 *  E. a stub was registered, so deleting the project still resolves its name
 *  F. after delete + refresh the source is gone and nothing leaked
 *
 * A failure between B and F can leave a source registered until the next app
 * start; it cannot corrupt anything, because nothing adds library entries.
 */
class JsRegistrationSpike(
    private val repository: JsProjectRepository,
    private val sourceManager: SourceManager,
) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    @Suppress("LongMethod")
    private suspend fun measure(): String {
        val id = JsProjectStore.idForSlug(SLUG)

        return try {
            buildString {
                appendLine("JsRegistrationSpike (id $id)")
                appendLine()

                repository.store.delete(SLUG)
                repository.refresh()
                appendLine("A. before: get(id)=${sourceManager.get(id)?.name ?: "null"}")

                repository.store.save(SLUG, manifest(), SCRIPT)
                repository.refresh()

                val registered = awaitSource(id, shouldExist = true)
                appendLine(
                    "B. after save + refresh: registered=${registered != null}, " +
                        "type=${registered?.let { it::class.simpleName }}, name='${registered?.name}'",
                )

                val all = sourceManager.getAll()
                val online = sourceManager.getOnlineSources()
                appendLine(
                    "C. in getAll()=${all.any { it.id == id }} (${all.size} sources), " +
                        "in getOnlineSources()=${online.any { it.id == id }} (expected false)",
                )

                if (registered !is JsProjectSource) {
                    appendLine("D. not registered as a project source, so browsing was skipped")
                } else {
                    // Through the manager's own instance, not a fresh one.
                    val page = registered.getPopularAnime(1)
                    appendLine(
                        "D. browsed via the manager's instance: ${page.animes.size} entries, " +
                            "first='${page.animes.firstOrNull()?.title}'",
                    )
                }

                val stub = sourceManager.getOrStub(id)
                appendLine("E. getOrStub(id) resolves to '${stub.name}' (${stub::class.simpleName})")

                repository.store.delete(SLUG)
                repository.refresh()
                val gone = awaitSource(id, shouldExist = false) == null
                appendLine("F. after delete + refresh: gone=$gone")
                appendLine("   stub still names it: '${sourceManager.getOrStub(id).name}'")
            }
        } catch (e: Throwable) {
            repository.store.delete(SLUG)
            repository.refresh()
            "JsRegistrationSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(14).joinToString("\n")
        }
    }

    /** Registration is flow-driven, so the map updates a moment after refresh. */
    private suspend fun awaitSource(id: Long, shouldExist: Boolean): AnimeSource? =
        withTimeoutOrNull(AWAIT_MILLIS) {
            var source = sourceManager.get(id)
            while ((source != null) != shouldExist) {
                delay(POLL_MILLIS)
                source = sourceManager.get(id)
            }
            source
        }

    private fun manifest() = JsProjectManifest(
        name = "Registration spike",
        lang = "en",
        baseUrl = BASE_URL,
        supportsLatest = false,
        nsfw = true,
        active = true,
    )

    private companion object {
        const val SLUG = "spike-registration"
        const val BASE_URL = "https://rule34video.com"
        const val AWAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L

        val SCRIPT = """
            var source = {
                meta: {
                    apiVersion: 1,
                    name: 'Registration spike',
                    lang: 'en',
                    baseUrl: 'https://rule34video.com',
                    nsfw: true
                },

                popular: function (args) {
                    var url = this.meta.baseUrl + '/latest-updates/' + (args.page || 1) + '/';
                    var doc = http.get(url, { 'Referer': this.meta.baseUrl + '/' }).html();
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
                }
            };
        """.trimIndent()
    }
}
