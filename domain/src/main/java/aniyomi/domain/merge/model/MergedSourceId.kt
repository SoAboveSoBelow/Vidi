// AM (MERGED_SOURCES) -->
package aniyomi.domain.merge.model

/**
 * Reserved pseudo-source id for merged library entries. The actual source
 * implementation (eu.kanade.tachiyomi.source.MergedSource) lives in the app
 * module and references this same value as its ID - defined here too because
 * domain-module code (GetAnimeWithEpisodesAndSeasons, etc.) needs to
 * recognize a merge parent and can't depend on the app module to get there.
 */
const val MERGED_SOURCE_ID: Long = -1L

/**
 * Internal season every merge child starts in: merged entries default to a
 * single shared season so all children's episodes show together in one flat
 * list. Splitting children into separate seasons is a later UI feature that
 * rewrites `merge_children.season_number`; see
 * aniyomi.domain.merge.interactor.GetMergedEpisodeList.
 */
const val MERGE_DEFAULT_SEASON_NUMBER: Long = 1L
// <-- AM (MERGED_SOURCES)
