package aniyomi.source.js

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 7 - project storage, host-minted identity, and draft gating.
 *
 * The adapter works; this is about the things that become permanent the moment
 * anything is persisted. Getting ids wrong is the expensive mistake, because a
 * library entry stores its source id and a changed id orphans it silently.
 *
 *  A. write two projects, one active and one draft
 *  B. list them - both present as projects
 *  C. ask for sources - only the active one, which is the draft gate
 *  D. ids are negative, not 0 or -1, and so cannot collide with an extension
 *  E. ids are stable across a fresh store, since they derive from the directory
 *  F. an unsupported apiVersion is refused rather than best-effort read
 *  G. the active project actually browses through the adapter
 *
 * Writes into `filesDir/js-projects/`, which survives until uninstalled or
 * cleaned up by the last phase.
 */
class JsProjectSpike(private val context: Context, private val network: NetworkHelper) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    @Suppress("LongMethod")
    private suspend fun measure(): String {
        val store = JsProjectStore(context)

        return try {
            buildString {
                appendLine("JsProjectSpike")
                appendLine()

                store.save(ACTIVE_SLUG, activeManifest(), ACTIVE_SCRIPT)
                store.save(DRAFT_SLUG, draftManifest(), DRAFT_SCRIPT)
                store.save(FUTURE_SLUG, futureManifest(), DRAFT_SCRIPT)
                appendLine("A. wrote 3 projects to ${store.rootPath()}")

                val projects = store.list()
                appendLine(
                    "B. list(): ${projects.size} projects - " +
                        projects.joinToString { "${it.slug}(active=${it.manifest.active})" },
                )

                val sources = store.activeSources(network)
                appendLine(
                    "C. activeSources(): ${sources.size} - ${sources.joinToString { it.name }} " +
                        "(draft excluded=${sources.none { it.name.contains("Draft") }})",
                )

                val activeId = JsProjectStore.idForSlug(ACTIVE_SLUG)
                val draftId = JsProjectStore.idForSlug(DRAFT_SLUG)
                appendLine(
                    "D. ids: active=$activeId draft=$draftId - " +
                        "negative=${activeId < 0 && draftId < 0}, " +
                        "reserved-clear=${activeId !in RESERVED && draftId !in RESERVED}, " +
                        "distinct=${activeId != draftId}",
                )

                val reread = JsProjectStore(context).read(ACTIVE_SLUG)
                appendLine(
                    "E. id stable across a fresh store: ${reread?.id == activeId} " +
                        "(${reread?.id})",
                )

                val refused = projects.none { it.slug == FUTURE_SLUG }
                appendLine("F. apiVersion 99 refused: $refused")

                val source = sources.firstOrNull()
                if (source == null) {
                    appendLine("G. no active source, so browsing was skipped")
                } else {
                    val page = source.getPopularAnime(1)
                    appendLine(
                        "G. browsed through the adapter: ${page.animes.size} entries, " +
                            "hasNextPage=${page.hasNextPage}, first='${page.animes.firstOrNull()?.title}'",
                    )
                    appendLine("   source id=${source.id} name='${source.name}' toString='$source'")
                    source.close()
                }

                appendLine()
                appendLine("cleaning up: ${listOf(ACTIVE_SLUG, DRAFT_SLUG, FUTURE_SLUG).map(store::delete)}")
            }
        } catch (e: Throwable) {
            "JsProjectSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(14).joinToString("\n")
        }
    }

    private fun activeManifest() = JsProjectManifest(
        name = "Rule34Video (project)",
        lang = "en",
        baseUrl = BASE_URL,
        supportsLatest = true,
        nsfw = true,
        active = true,
    )

    private fun draftManifest() = JsProjectManifest(
        name = "Draft project",
        lang = "en",
        baseUrl = BASE_URL,
        active = false,
    )

    private fun futureManifest() = JsProjectManifest(
        name = "From the future",
        lang = "en",
        baseUrl = BASE_URL,
        apiVersion = 99,
        active = true,
    )

    private companion object {
        const val BASE_URL = "https://rule34video.com"
        const val ACTIVE_SLUG = "spike-active"
        const val DRAFT_SLUG = "spike-draft"
        const val FUTURE_SLUG = "spike-future"

        val RESERVED = listOf(0L, -1L)

        /** A real listing project, same contract as the adapter spike. */
        val ACTIVE_SCRIPT = """
            var source = {
                meta: {
                    apiVersion: 1,
                    name: 'Rule34Video (project)',
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

        /** Deliberately incomplete, which is what a draft looks like. */
        val DRAFT_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Draft project', lang: 'en', baseUrl: '', nsfw: false }
            };
        """.trimIndent()
    }
}
