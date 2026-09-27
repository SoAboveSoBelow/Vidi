// AM (CUSTOM_INFORMATION) -->
package tachiyomi.data.anime

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.anime.model.CustomAnimeInfo
import tachiyomi.domain.anime.repository.CustomAnimeRepository
import java.io.File

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CustomAnimeRepositoryImpl(context: Context) : CustomAnimeRepository {
    private val editJson = File(context.getExternalFilesDir(null), "edits.json")

    private val customAnimeMap = fetchCustomData()

    override fun get(animeId: Long) = customAnimeMap[animeId]

    private fun fetchCustomData(): MutableMap<Long, CustomAnimeInfo> {
        if (!editJson.exists() || !editJson.isFile) return mutableMapOf()

        val json = try {
            Json.decodeFromString<AnimeList>(
                editJson.bufferedReader().use { it.readText() },
            )
        } catch (e: Exception) {
            null
        } ?: return mutableMapOf()

        val animesJson = json.animes ?: return mutableMapOf()
        return animesJson
            .mapNotNull { animeJson ->
                val id = animeJson.id ?: return@mapNotNull null
                id to animeJson.toAnime()
            }
            .toMap()
            .toMutableMap()
    }

    override fun set(animeInfo: CustomAnimeInfo) {
        if (
            animeInfo.title == null &&
            animeInfo.author == null &&
            animeInfo.artist == null &&
            animeInfo.description == null &&
            animeInfo.genre == null &&
            animeInfo.addedGenre == null &&
            animeInfo.status == null
        ) {
            customAnimeMap.remove(animeInfo.id)
        } else {
            customAnimeMap[animeInfo.id] = animeInfo
        }
        saveCustomInfo()
    }

    private fun saveCustomInfo() {
        val jsonElements = customAnimeMap.values.map { it.toJson() }
        // AM (TAG_LIMIT) -->
        // The delete happens either way. Skipping the whole write when nothing is left
        // meant clearing the last entry's custom info left the previous file on disk,
        // so it came back on the next read - removing the last edit never stuck while
        // adding one always did.
        editJson.delete()
        if (jsonElements.isNotEmpty()) {
            editJson.writeText(Json.encodeToString(AnimeList(jsonElements)))
        }
        // <-- AM (TAG_LIMIT)
    }

    @Serializable
    data class AnimeList(
        val animes: List<AnimeJson>? = null,
    )

    @Serializable
    data class AnimeJson(
        var id: Long? = null,
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        // AM (TAG_LIMIT) -->
        val addedGenre: List<String>? = null,
        // <-- AM (TAG_LIMIT)
        val status: Long? = null,
    ) {

        fun toAnime() = CustomAnimeInfo(
            id = this@AnimeJson.id!!,
            title = this@AnimeJson.title?.takeUnless { it.isBlank() },
            author = this@AnimeJson.author,
            artist = this@AnimeJson.artist,
            description = this@AnimeJson.description,
            genre = this@AnimeJson.genre,
            // AM (TAG_LIMIT) -->
            addedGenre = this@AnimeJson.addedGenre,
            // <-- AM (TAG_LIMIT)
            status = this@AnimeJson.status?.takeUnless { it == 0L },
        )
    }

    private fun CustomAnimeInfo.toJson(): AnimeJson {
        return AnimeJson(
            id = id,
            title = title,
            author = author,
            artist = artist,
            description = description,
            genre = genre,
            // AM (TAG_LIMIT) -->
            addedGenre = addedGenre,
            // <-- AM (TAG_LIMIT)
            status = status,
        )
    }
}
// <-- AM (CUSTOM_INFORMATION)
