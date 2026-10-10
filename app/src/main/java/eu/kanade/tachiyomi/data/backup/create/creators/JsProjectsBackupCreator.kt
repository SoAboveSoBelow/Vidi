// AM (JS_PROJECT_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.create.creators

import aniyomi.source.js.JsProjectRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupJsProject

@Inject
class JsProjectsBackupCreator(
    private val repository: JsProjectRepository,
) {

    operator fun invoke(): List<BackupJsProject> = repository.store.rawEntries().map {
        BackupJsProject(slug = it.slug, manifest = it.manifest, script = it.script)
    }
}
// <-- AM (JS_PROJECT_SOURCES)
