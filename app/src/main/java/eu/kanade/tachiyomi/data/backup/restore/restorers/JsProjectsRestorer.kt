// AM (JS_PROJECT_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import aniyomi.source.js.JsProjectRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupJsProject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
class JsProjectsRestorer(
    private val repository: JsProjectRepository,
) {

    /**
     * Existing projects are left alone, the way [ExtensionsRestorer] skips an
     * already-installed package. A restore should not be able to overwrite work
     * the user has done since the backup was taken, and the slug is the
     * project's identity, so a collision means the same project.
     *
     * Returns how many were written.
     */
    fun restoreJsProjects(projects: List<BackupJsProject>): Int {
        if (projects.isEmpty()) return 0

        var written = 0
        projects.forEach { project ->
            if (repository.store.exists(project.slug)) {
                logcat(LogPriority.INFO) { "Keeping existing project '${project.slug}' over the backup copy" }
            } else {
                try {
                    repository.store.writeRaw(project.slug, project.manifest, project.script)
                    written++
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not restore project '${project.slug}'" }
                }
            }
        }
        repository.refresh()
        return written
    }
}
// <-- AM (JS_PROJECT_SOURCES)
