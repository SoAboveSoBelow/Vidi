// AM (JS_PROJECT_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A JavaScript project as it travels in a backup.
 *
 * Both files are carried as raw text rather than as a decoded manifest, so the
 * backup format does not have to track the manifest schema - which versions
 * itself through its own `apiVersion` - and a project whose manifest no longer
 * parses still survives a backup and restore.
 */
@Serializable
data class BackupJsProject(
    @ProtoNumber(1) val slug: String,
    @ProtoNumber(2) val manifest: String,
    @ProtoNumber(3) val script: String,
)
// <-- AM (JS_PROJECT_SOURCES)
