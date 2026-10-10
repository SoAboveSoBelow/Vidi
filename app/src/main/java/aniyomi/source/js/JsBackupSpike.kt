package aniyomi.source.js

import eu.kanade.tachiyomi.data.backup.create.creators.JsProjectsBackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.JsProjectsRestorer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 10 - do projects survive a backup and restore?
 *
 * Exercises the creator and restorer directly rather than writing a whole
 * `.tachibk`, because the question is about the project section, not about
 * protobuf or file IO which the rest of the backup already proves.
 *
 *  A. three projects on disk: active, draft, and one with a manifest that does
 *     not parse
 *  B. the creator carries all three, including the broken one - a backup that
 *     drops unparseable work is worse than no backup
 *  C. after deleting them, the restorer writes them all back
 *  D. byte-for-byte: manifest and script come back exactly as written
 *  E. an existing project is kept rather than overwritten by the backup copy
 *
 * Cleans up after itself, on the failure path too.
 */
class JsBackupSpike(
    private val repository: JsProjectRepository,
    private val creator: JsProjectsBackupCreator,
    private val restorer: JsProjectsRestorer,
) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private fun measure(): String {
        val store = repository.store

        return try {
            buildString {
                appendLine("JsBackupSpike")
                appendLine()

                SLUGS.forEach { store.delete(it) }
                store.writeRaw(ACTIVE_SLUG, ACTIVE_MANIFEST, SCRIPT)
                store.writeRaw(DRAFT_SLUG, DRAFT_MANIFEST, SCRIPT)
                store.writeRaw(BROKEN_SLUG, BROKEN_MANIFEST, SCRIPT)
                repository.refresh()

                val readable = store.listAll().count { it is JsProjectEntry.Readable }
                val unreadable = store.listAll().count { it is JsProjectEntry.Unreadable }
                appendLine("A. on disk: $readable readable, $unreadable unreadable")

                val backed = creator()
                val mine = backed.filter { it.slug in SLUGS }
                appendLine(
                    "B. creator carried ${mine.size} of ${SLUGS.size} " +
                        "(broken included=${mine.any { it.slug == BROKEN_SLUG }}), " +
                        "${backed.size} total in the backup",
                )

                SLUGS.forEach { store.delete(it) }
                repository.refresh()
                val afterDelete = store.listAll().count { it.slug in SLUGS }

                val written = restorer.restoreJsProjects(mine)
                val afterRestore = store.listAll().count { it.slug in SLUGS }
                appendLine(
                    "C. deleted (left $afterDelete), restored $written, now $afterRestore back",
                )

                val roundTripped = store.rawEntries().filter { it.slug in SLUGS }
                val exact = roundTripped.all { raw ->
                    val source = mine.first { it.slug == raw.slug }
                    raw.manifest == source.manifest && raw.script == source.script
                }
                appendLine("D. byte-for-byte identical: $exact")

                val secondPass = restorer.restoreJsProjects(mine)
                val activeStillDraftFree = store.read(ACTIVE_SLUG)?.manifest?.active
                appendLine(
                    "E. restoring again wrote $secondPass (expected 0 - existing kept), " +
                        "active project intact=${activeStillDraftFree == true}",
                )
            }
        } catch (e: Throwable) {
            "JsBackupSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(14).joinToString("\n")
        } finally {
            SLUGS.forEach { store.delete(it) }
            repository.refresh()
        }
    }

    private companion object {
        const val ACTIVE_SLUG = "backup-active"
        const val DRAFT_SLUG = "backup-draft"
        const val BROKEN_SLUG = "backup-broken"

        val SLUGS = listOf(ACTIVE_SLUG, DRAFT_SLUG, BROKEN_SLUG)

        const val ACTIVE_MANIFEST =
            """{"name":"Backup active","lang":"en","baseUrl":"https://example.com","active":true}"""
        const val DRAFT_MANIFEST =
            """{"name":"Backup draft","lang":"en","baseUrl":"https://example.com","active":false}"""

        /** Truncated on purpose: this is what a half-edited manifest looks like. */
        const val BROKEN_MANIFEST = """{"name":"Backup broken","lang":"en","""

        val SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Backup', lang: 'en', baseUrl: '', nsfw: false },
                popular: function (args) { return { entries: [], hasNextPage: false }; }
            };
        """.trimIndent()
    }
}
