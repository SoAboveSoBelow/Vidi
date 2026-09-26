package eu.kanade.tachiyomi.util

import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.toSAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.anime.model.Anime
import tachiyomi.source.local.image.LocalBackgroundManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalEpisodeThumbnailManager
import tachiyomi.source.local.isLocal
import java.io.InputStream
import kotlin.time.Clock

// AM (ART_SURVIVES_UNFAVORITE) -->
// Was called from every unfavorite path, deleting the custom cover outright the
// moment an entry left the library - so a misclicked "remove from library" lost
// a hand-set cover permanently, with no undo, even though the anime row itself
// survives and re-adding the entry restores everything else about it.
//
// Merge membership and episode overrides already made the opposite call
// deliberately (see AnimeViewModel.toggleFavorite): they outlive unfavoriting
// and are reaped by foreign-key cascade when the row is actually deleted, which
// today only happens via Settings > Advanced > Clear database. Custom art is
// user-authored data in exactly the same sense, so it now follows the same
// policy - the file stays, keyed to a row that still exists, and goes when that
// row goes.
//
// Kept (rather than deleted along with its call sites) for the paths that
// genuinely destroy art on purpose: a user clearing a custom cover, and the
// cache-wide cleanups in Settings > Advanced.
// <-- AM (ART_SURVIVES_UNFAVORITE)
fun Anime.removeCovers(coverCache: CoverCache): Anime {
    if (isLocal()) return this
    return if (coverCache.deleteFromCache(this, true) > 0) {
        copy(coverLastModified = Clock.System.now().toEpochMilliseconds())
    } else {
        this
    }
}

// AY -->
fun Anime.removeBackgrounds(backgroundCache: BackgroundCache): Anime {
    if (isLocal()) return this
    return if (backgroundCache.deleteFromCache(this, true) > 0) {
        copy(backgroundLastModified = Clock.System.now().toEpochMilliseconds())
    } else {
        this
    }
}
// <-- AY

suspend fun Anime.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateAnime: UpdateAnime,
    coverCache: CoverCache,
) {
    if (isLocal()) {
        coverManager.update(toSAnime(), stream)
        updateAnime.awaitUpdateCoverLastModified(id)
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateAnime.awaitUpdateCoverLastModified(id)
    }
}

// AY -->
suspend fun Anime.editBackground(
    backgroundManager: LocalBackgroundManager,
    stream: InputStream,
    updateAnime: UpdateAnime,
    backgroundCache: BackgroundCache,
) {
    if (isLocal()) {
        backgroundManager.update(toSAnime(), stream)
        updateAnime.awaitUpdateBackgroundLastModified(id)
    } else if (favorite) {
        backgroundCache.setCustomBackgroundToCache(this, stream)
        updateAnime.awaitUpdateBackgroundLastModified(id)
    }
}

fun SEpisode.editThumbnail(
    anime: Anime,
    thumbnailManager: LocalEpisodeThumbnailManager,
    stream: InputStream,
) {
    if (anime.isLocal()) {
        thumbnailManager.update(anime.toSAnime(), this, stream)
    }
}
// <-- AY
