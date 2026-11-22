package eu.kanade.presentation.anime

enum class DownloadAction {
    NEXT_1_EPISODE,
    NEXT_5_EPISODES,
    NEXT_10_EPISODES,
    NEXT_25_EPISODES,
    UNSEEN_EPISODES,
    BOOKMARKED_EPISODES,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
}

enum class AnimeScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,
    EPISODE_HEADER,
    EPISODE,

    // AM (ENTRY_SCROLLER): a card, not a row - its own content type so the
    // two are never recycled into one another.
    EPISODE_SCROLLER,

    // AY -->
    AIRING_TIME,
    // <-- AY

    // AM (CUSTOM_EPISODE_ORDER) -->
    SEASON_SWITCHER,
    // <-- AM (CUSTOM_EPISODE_ORDER)
}
