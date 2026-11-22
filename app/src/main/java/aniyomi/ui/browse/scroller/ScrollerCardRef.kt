package aniyomi.ui.browse.scroller

import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode

// AM (ENTRY_SCROLLER) -->
/**
 * What one card in a feed plays.
 *
 * The feed was written when there was only one kind of card: an entry from a
 * source's listing, whose media is the entry's own single episode. The entry
 * screen's scroller view puts a second kind beside it - one card per episode
 * of ONE entry - and the two differ in exactly two things: what the card is
 * keyed by, and whether the episode behind it is already known.
 *
 * Everything else in [ScrollerPlaybackState] is keyed by a Long and does not
 * care which of the two it is holding, so this is the only place the
 * distinction lives rather than a second engine beside the first.
 *
 * [id] is that key, and it is NOT always an anime id: it is the entry's id for
 * a listing card and the episode's id for an entry card. The two never share a
 * feed, and a feed's state is keyed by [ScrollerSession.FeedKey], so the two
 * id spaces cannot meet in one map.
 */
data class ScrollerCardRef(
    val id: Long,
    val anime: Anime,
    /**
     * The episode this card plays, where it is already known.
     *
     * Non-null spares the resolver an episode-list request per card: the entry
     * screen's rows are already in the database, so the only thing left to ask
     * a source for is the video url - and nothing at all for an episode that
     * is downloaded, which [EpisodeLoader] answers from disk.
     */
    val episode: Episode?,
    /**
     * Whether the entry's own browse media ([ScrollerFeedMedia] in
     * `anime.memo`) describes this card.
     *
     * True only for an entry whose source gives it a single episode, where the
     * entry IS the clip. On a multi-episode entry the memo describes one of
     * them at most, and letting every card fall back to it would have the
     * whole feed play the same clip.
     */
    val mayUseEntryMedia: Boolean = false,
) {
    /**
     * What this card's own listing states about its media, if anything.
     *
     * The episode's memo first, the entry's only where [mayUseEntryMedia]
     * allows it. One definition, read by both the resolver and the geometry
     * pass, so the two cannot disagree about which card a memo describes.
     */
    fun statedMedia(): ScrollerFeedMedia? = when {
        episode != null -> ScrollerFeedMedia.from(episode.memo)
            ?: ScrollerFeedMedia.from(anime).takeIf { mayUseEntryMedia }
        else -> ScrollerFeedMedia.from(anime)
    }

    companion object {
        /** A card in a source's own listing feed. */
        fun ofEntry(anime: Anime) = ScrollerCardRef(id = anime.id, anime = anime, episode = null)

        /** A card in an entry's episode feed. */
        fun ofEpisode(anime: Anime, episode: Episode, singleEpisodeSource: Boolean) = ScrollerCardRef(
            id = episode.id,
            anime = anime,
            episode = episode,
            mayUseEntryMedia = singleEpisodeSource,
        )
    }
}
// <-- AM (ENTRY_SCROLLER)
