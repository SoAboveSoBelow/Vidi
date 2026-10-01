package eu.kanade.tachiyomi.ui.player

import animiru.domain.player.model.VideoAspect
import dev.icerock.moko.resources.StringResource
// AM (PLAYER_ADD_TO_LIBRARY) -->
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeWithEpisodeCount
import tachiyomi.domain.category.model.Category
// <-- AM (PLAYER_ADD_TO_LIBRARY)

enum class Sheets {
    None,
    PlaybackSpeed,
    SubtitleTracks,
    AudioTracks,
    QualityTracks,
    Chapters,
    More,
    Screenshot,
}

enum class Panels {
    None,
    SubtitleSettings,
    SubtitleDelay,
    AudioDelay,
    VideoFilters,
}

sealed class Dialogs {
    data object None : Dialogs()
    data object EpisodeList : Dialogs()
    data class IntegerPicker(
        val defaultValue: Int,
        val minValue: Int,
        val maxValue: Int,
        val step: Int,
        val nameFormat: String,
        val title: String,
        val onChange: (Int) -> Unit,
        val onDismissRequest: () -> Unit,
    ) : Dialogs()

    // AM (PLAYER_ADD_TO_LIBRARY) -->
    // The two questions the entry screen's heart can ask on the way into the
    // library, so adding from the player asks them the same way instead of
    // quietly answering them itself.
    data class DuplicateAnime(
        val anime: Anime,
        val duplicates: List<AnimeWithEpisodeCount>,
    ) : Dialogs()

    data class ChangeCategory(
        val anime: Anime,
        val initialSelection: List<CheckboxState<Category>>,
    ) : Dialogs()

    /** Offered after a removal, as the entry screen offers it in a snackbar. */
    data class DeleteDownloadsAfterRemoval(val anime: Anime) : Dialogs()
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
}

sealed class PlayerUpdates {
    data object None : PlayerUpdates()
    data object DoubleSpeed : PlayerUpdates()
    data class AspectRatio(val aspect: VideoAspect) : PlayerUpdates()
    data class ShowText(val value: String) : PlayerUpdates()
    data class ShowTextResource(val textResource: StringResource) : PlayerUpdates()
}
