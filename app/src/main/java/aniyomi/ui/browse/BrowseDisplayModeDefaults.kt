package aniyomi.ui.browse

import aniyomi.domain.source.model.BrowseDisplayMode
import eu.kanade.tachiyomi.animesource.AnimeSource

// AM (BROWSE_SCROLLER) -->
/**
 * A source may declare which browse layout suits it - a feed source is close
 * to useless as a grid of covers - without that becoming a setting the user
 * cannot escape. It only seeds the resolution in BrowseSourceViewModel: a
 * per-source override the user sets always wins.
 *
 * Sources built into the app declare it by implementing
 * [DeclaresBrowseDisplayMode]. Extension APKs cannot implement an app-side
 * interface, so they are declared here by id instead.
 */
interface DeclaresBrowseDisplayMode {
    val defaultBrowseDisplayMode: BrowseDisplayMode
}

object BrowseDisplayModeDefaults {

    private val byId = emptyMap<Long, BrowseDisplayMode>()

    fun forSource(source: AnimeSource): BrowseDisplayMode? {
        return (source as? DeclaresBrowseDisplayMode)?.defaultBrowseDisplayMode ?: byId[source.id]
    }
}
// <-- AM (BROWSE_SCROLLER)
