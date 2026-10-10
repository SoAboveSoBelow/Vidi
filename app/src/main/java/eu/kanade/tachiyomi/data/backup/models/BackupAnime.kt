package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.JsonObjectEmptyBytes
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.CustomAnimeInfo

@Suppress("DEPRECATION")
@Serializable
data class BackupAnime(
    // in 1.x some of these values have different names
    @ProtoNumber(1) var source: Long,
    // url is called key in 1.x
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    // thumbnailUrl is called cover in 1.x
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    // @ProtoNumber(10) val customCover: String = "", 1.x value, not used in 0.x
    // @ProtoNumber(11) val lastUpdate: Long = 0, 1.x value, not used in 0.x
    // @ProtoNumber(12) val lastInit: Long = 0, 1.x value, not used in 0.x
    @ProtoNumber(13) var dateAdded: Long = 0,
    // @ProtoNumber(15) val flags: Int = 0, 1.x value, not used in 0.x
    @ProtoNumber(16) var episodes: List<BackupEpisode> = emptyList(),
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(18) var tracking: List<BackupTracking> = emptyList(),
    // Bump by 100 for values that are not saved/implemented in 1.x but are used in 0.x
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var episodeFlags: Int = 0,
    // @ProtoNumber(102) var brokenHistory, legacy history model with non-compliant proto number
    @ProtoNumber(103) var viewer_flags: Int = 0,
    @ProtoNumber(104) var history: List<BackupHistory> = emptyList(),
    @ProtoNumber(105) var updateStrategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    // Mihon values start here
    @ProtoNumber(109) var version: Long = 0,
    @ProtoNumber(110) var notes: String = "",
    @ProtoNumber(111) var initialized: Boolean = false,
    @ProtoNumber(112) var memo: ByteArray = JsonObjectEmptyBytes,

    // AM (CUSTOM_INFORMATION) -->
    // Bump values by 200
    @ProtoNumber(200) var customStatus: Int = 0,
    @ProtoNumber(201) var customTitle: String? = null,
    @ProtoNumber(202) var customArtist: String? = null,
    @ProtoNumber(203) var customAuthor: String? = null,
    @ProtoNumber(204) var customDescription: String? = null,
    @ProtoNumber(205) var customGenre: List<String>? = null,
    // AM (TAG_LIMIT) -->
    @ProtoNumber(210) var customAddedGenre: List<String>? = null,
    // 211 held tagVisibility, and 0.19.16 added customBlacklistedGenre on top of
    // it. Both were written under the one number, so a 0.19.16 backup carries
    // two incompatible shapes at 211: a bare string and a nested message. A
    // reader bound to either one decodes the other's bytes as its own and the
    // length prefix it reads is wrong, which desynchronizes the rest of the
    // anime record rather than losing a single field.
    // Burned rather than reassigned to one of them: leaving the number unbound
    // is what lets those backups restore at all, since an unknown field is
    // skipped by its wire type and costs only the two tag fields. Both moved to
    // the 700 band below.
    // <-- AM (TAG_LIMIT)
    // <-- AM (CUSTOM_INFORMATION)
    // Since ProtoNumber 108 was previously used in the past, we cannot reuse it. May cause issues with aniyomi
    // AM -->
    @ProtoNumber(206) var excludedScanlators: List<String> = emptyList(),
    // <-- AM

    // AY -->
    // Aniyomi specific values
    @ProtoNumber(500) var backgroundUrl: String? = null,
    // @ProtoNumber(501) Broken in aniyomi, do not use
    @ProtoNumber(502) var parentId: Long? = null,
    @ProtoNumber(503) var id: Long? = null, // Used to associate seasons with parents. Do not use for anything else.
    @ProtoNumber(504) var seasonFlags: Long = 0,
    @ProtoNumber(505) var seasonNumber: Double = -1.0,
    @ProtoNumber(506) var seasonSourceOrder: Long = 0,
    @ProtoNumber(507) var fetchType: FetchType = FetchType.Episodes,
    // <-- AY

    // AM -->
    // Vidi specific values. 700 band: Mihon grows by ones from 112, Aniyomi owns
    // 500 and TachiyomiSY owns 600 and 800, so 700 is the one range no fork in
    // this lineage has claimed. Keeping Vidi's own structural features in a band
    // of their own is what stops a backup read by another fork, or an upstream
    // field added later, from landing on top of them.
    // 700 and 701 were frame-grab provenance for the cover and background, since
    // withdrawn. Not reused: a backup written while they existed still carries
    // them, and a reader that reinterpreted those bytes as something else would
    // decode them wrong rather than ignore them.
    @ProtoNumber(702) var entrySeasons: List<BackupEntrySeason> = emptyList(),
    @ProtoNumber(703) var episodeOrder: List<BackupEpisodeOrder> = emptyList(),
    @ProtoNumber(704) var mergeChildren: List<BackupMergeChild> = emptyList(),
    @ProtoNumber(705) var mergeSettings: BackupMergeSettings? = null,
    @ProtoNumber(706) var mergeExclusions: List<BackupMergeExclusion> = emptyList(),
    // AM (TAG_LIMIT) / AM (TAG_BLACKLIST): rehoused from 211, which is burned.
    @ProtoNumber(707) var tagVisibility: List<BackupTagVisibility> = emptyList(),
    @ProtoNumber(708) var customBlacklistedGenre: List<String>? = null,
    // <-- AM
) {
    fun getAnimeImpl(): Anime {
        return Anime.create().copy(
            url = this@BackupAnime.url,
            // AM (CUSTOM_INFORMATION) -->
            ogTitle = this@BackupAnime.title,
            ogArtist = this@BackupAnime.artist,
            ogAuthor = this@BackupAnime.author,
            ogDescription = this@BackupAnime.description,
            ogGenre = this@BackupAnime.genre,
            ogStatus = this@BackupAnime.status.toLong(),
            // <-- AM (CUSTOM_INFORMATION)
            thumbnailUrl = this@BackupAnime.thumbnailUrl,
            // AY -->
            backgroundUrl = this@BackupAnime.backgroundUrl,
            // <-- AY
            favorite = this@BackupAnime.favorite,
            source = this@BackupAnime.source,
            dateAdded = this@BackupAnime.dateAdded,
            viewerFlags = this@BackupAnime.viewer_flags.toLong(),
            episodeFlags = this@BackupAnime.episodeFlags.toLong(),
            updateStrategy = this@BackupAnime.updateStrategy,
            lastModifiedAt = this@BackupAnime.lastModifiedAt,
            favoriteModifiedAt = this@BackupAnime.favoriteModifiedAt,
            version = this@BackupAnime.version,
            notes = this@BackupAnime.notes,
            initialized = this@BackupAnime.initialized,
            // AY -->
            fetchType = this@BackupAnime.fetchType,
            parentId = this@BackupAnime.parentId,
            seasonFlags = this@BackupAnime.seasonFlags,
            seasonNumber = this@BackupAnime.seasonNumber,
            seasonSourceOrder = this@BackupAnime.seasonSourceOrder,
            // <-- AY
            memo = MemoColumnAdapter.decode(this@BackupAnime.memo),
        )
    }

    // AM (CUSTOM_INFORMATION) -->
    fun getCustomAnimeInfo(): CustomAnimeInfo? {
        if (customTitle != null ||
            customArtist != null ||
            customAuthor != null ||
            customDescription != null ||
            customGenre != null ||
            customAddedGenre != null ||
            // AM (TAG_BLACKLIST)
            customBlacklistedGenre != null ||
            customStatus != 0
        ) {
            return CustomAnimeInfo(
                id = 0L,
                title = customTitle,
                author = customAuthor,
                artist = customArtist,
                description = customDescription,
                genre = customGenre,
                addedGenre = customAddedGenre,
                // AM (TAG_BLACKLIST)
                blacklistedGenre = customBlacklistedGenre,
                status = customStatus.takeUnless { it == 0 }?.toLong(),
            )
        }
        return null
    }
    // <-- AM (CUSTOM_INFORMATION)
}
