package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME) -->
/**
 * The proto field number of [Backup.backupAnime].
 *
 * Named because [BackupAnimeChunk] has to carry the same one, and a literal in
 * two places is a literal that can drift.
 */
const val BACKUP_ANIME_PROTO_NUMBER = 501
// <-- AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME)

// AY -->
@Serializable
data class LegacyBackup(
    @ProtoNumber(3) val backupAnime: List<BackupAnime>,
    @ProtoNumber(4) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(103) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensions: List<BackupExtension> = emptyList(),
    @ProtoNumber(107) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    @ProtoNumber(109) var backupCustomButton: List<BackupCustomButtons> = emptyList(),
) {
    fun toBackup(): Backup {
        return Backup(
            isLegacy = false, // Only used for detection
            backupAnime = backupAnime,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupExtensions = backupExtensions,
            backupExtensionStores = backupExtensionStores,
            backupCustomButton = backupCustomButton,
        )
    }
}
// <-- AY

@Serializable
data class Backup(
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),

    // AY -->
    // Aniyomi specific values
    @ProtoNumber(500) val isLegacy: Boolean = true,
    @ProtoNumber(BACKUP_ANIME_PROTO_NUMBER) val backupAnime: List<BackupAnime>,
    @ProtoNumber(502) var backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(503) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(504) var backupExtensions: List<BackupExtension> = emptyList(),
    @ProtoNumber(505) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    @ProtoNumber(506) var backupCustomButton: List<BackupCustomButtons> = emptyList(),
    // <-- AY
    // AM (JS_PROJECT_SOURCES) -->
    @ProtoNumber(507) var backupJsProjects: List<BackupJsProject> = emptyList(),
    // <-- AM (JS_PROJECT_SOURCES)
)

// AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME) -->

/**
 * A single [Backup.backupAnime] element, encodable on its own.
 *
 * BackupCreator used to build every entry in memory, hand the whole [Backup] to
 * `encodeToByteArray`, and gzip the array it got back - so peak memory held the
 * entire object graph AND a complete copy of its bytes. At a hundred thousand
 * viewed entries, each with its episodes and history, that is the wall a large
 * library hits first, well before any query gets slow.
 *
 * kotlinx's protobuf has no streaming encoder, but the wire format does not
 * need one. A repeated message field is written as one length-delimited record
 * per element, records may appear in any order, and concatenating encoded
 * messages merges them - so writing the non-entry fields as one message and
 * then each entry as its own record produces a byte-for-byte equivalent of the
 * single-shot encode. Peak memory becomes one entry plus the non-entry fields.
 *
 * This wrapper exists so those per-entry records are produced BY the
 * serializer, from [BACKUP_ANIME_PROTO_NUMBER], rather than by hand-writing a
 * field tag. Encoding it yields exactly `tag + length + <entry bytes>` and
 * nothing else, because an absent list contributes no bytes.
 */
@Serializable
data class BackupAnimeChunk(
    @ProtoNumber(BACKUP_ANIME_PROTO_NUMBER) val backupAnime: List<BackupAnime>,
)
// <-- AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME)
