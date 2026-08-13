package eu.kanade.tachiyomi.ui.player

import animiru.domain.player.model.VideoAspect
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.castIncluded
import tachiyomi.i18n.aniyomi.AYMR

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

/**
 * Buttons that can be shown in the customizable row along the bottom of the player.
 * Order in [entries] is only the declaration order; the user's chosen order and
 * selection are stored separately via [animiru.domain.player.service.GesturePreferences.bottomPlayerButtons].
 */
enum class BottomPlayerButton(val stringRes: StringResource) {
    Lock(AYMR.strings.pref_bottom_button_lock),
    Rotation(AYMR.strings.pref_bottom_button_rotation),
    Speed(AYMR.strings.pref_bottom_button_speed),
    Chapters(AYMR.strings.pref_bottom_button_chapters),
    PictureInPicture(AYMR.strings.pref_bottom_button_pip),
    AspectRatio(AYMR.strings.pref_bottom_button_aspect_ratio),
    Screenshot(AYMR.strings.pref_bottom_button_screenshot),
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
}

sealed class PlayerUpdates {
    data object None : PlayerUpdates()
    data object DoubleSpeed : PlayerUpdates()
    data class AspectRatio(val aspect: VideoAspect) : PlayerUpdates()
    data class ShowText(val value: String) : PlayerUpdates()
    data class ShowTextResource(val textResource: StringResource) : PlayerUpdates()
}
