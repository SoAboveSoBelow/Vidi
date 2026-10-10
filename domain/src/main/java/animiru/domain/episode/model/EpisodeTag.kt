// AM (EPISODE_TAGS) -->
package animiru.domain.episode.model

/**
 * One row of [episode_tags]: a tag the user put on an episode ([visible] true),
 * or an inherited tag they took off it ([visible] false).
 *
 * [tag] is stored as the user typed it and matched case-insensitively, so a
 * manually added "Filler" and an inherited "filler" are one tag.
 */
data class EpisodeTag(
    val episodeId: Long,
    val tag: String,
    val visible: Boolean,
)

/**
 * What one host's episodes are tagged with, once the user's rows have been laid
 * over the tags they inherit from the entries that own them.
 *
 * Built by [resolveEpisodeTags], which is the single place the rules live.
 */
data class EpisodeTagResolution(
    /** Effective tags per episode id - what that episode shows and what it matches. */
    val byEpisodeId: Map<Long, List<String>>,
    /**
     * Lowercased tag -> how many episodes the user put it on. This is the count
     * the entry's tag popout shows, and it counts only manual rows: an inherited
     * tag would otherwise read as the entry's whole episode count, which says
     * nothing.
     */
    val counts: Map<String, Int>,
    /**
     * Lowercased tags that every episode of the host would carry, so they are
     * hidden by default - see [resolveEpisodeTags].
     */
    val hiddenByDefault: Set<String>,
    /** Tags that exist only because the user put them on episodes, in count order. */
    val episodeOnly: List<String>,
    /**
     * Per episode id, what it would carry with no rows of its own - what a row
     * is written against, so a tag put back where it started stores nothing.
     */
    val defaultByEpisodeId: Map<Long, List<String>> = emptyMap(),
) {
    companion object {
        val Empty = EpisodeTagResolution(emptyMap(), emptyMap(), emptySet(), emptyList())
    }
}

/**
 * Resolves what each of a host's episodes is tagged with.
 *
 * Two rules, at two different scopes, which is the whole of it:
 *
 * 1. **Manual tags override inheritance, per owning entry.** The moment any
 *    episode of an entry carries a manual row for a tag, that tag belongs to
 *    those episodes alone within that entry - its other episodes stop inheriting
 *    it. Inside a merge that is scoped to the one child: tagging an episode of
 *    source A with "action" takes A's other episodes off it and leaves source
 *    B's alone.
 *
 * 2. **An inherited tag every episode would carry is hidden by default.** A tag
 *    that covers the whole host tells the user nothing and makes searching it
 *    return everything, which is the same as returning nothing. On an ordinary
 *    entry that is all of its own tags. Inside a merge it is only the tags every
 *    source agrees on - a tag one child has and another does not still separates
 *    them, so it stays. Nothing is stored for this; it is recomputed from what
 *    the host is showing.
 *
 * Rule 1 is scoped to the owner, rule 2 to the host, and they are evaluated in
 * that order: a manual tag is never hidden by rule 2, because the user put it
 * on that episode and not on the others.
 *
 * @param episodesByOwner the host's episode ids grouped by the entry that owns
 *   them - one entry for an ordinary host, one per source for a merge.
 * @param ownerTags each owning entry's own tags.
 * @param rows every [EpisodeTag] row for those episodes.
 */
fun resolveEpisodeTags(
    episodesByOwner: Map<Long, List<Long>>,
    ownerTags: Map<Long, List<String>>,
    rows: List<EpisodeTag>,
): EpisodeTagResolution {
    if (episodesByOwner.isEmpty()) return EpisodeTagResolution.Empty

    val rowsByEpisode = rows.groupBy { it.episodeId }

    // Rule 1's input: per owner, the tags the user has placed on any of its
    // episodes. Those tags stop being inherited by that owner's other episodes.
    val manualByOwner = episodesByOwner.mapValues { (_, episodeIds) ->
        episodeIds
            .flatMap { rowsByEpisode[it].orEmpty() }
            .filter { it.visible }
            .mapTo(HashSet()) { it.tag.lowercase() }
    }

    // Rule 2's input: the tags every owner contributing episodes lists. An
    // ordinary host has one owner, so this is simply all of its tags.
    val hiddenByDefault = episodesByOwner.keys
        .map { owner -> ownerTags[owner].orEmpty().mapTo(HashSet()) { it.lowercase() } }
        .reduceOrNull { shared, next -> shared.apply { retainAll(next) } }
        .orEmpty()

    val byEpisodeId = HashMap<Long, List<String>>()
    val defaultByEpisodeId = HashMap<Long, List<String>>()
    val counts = HashMap<String, Int>()
    episodesByOwner.forEach { (owner, episodeIds) ->
        val inherited = ownerTags[owner].orEmpty()
        val overridden = manualByOwner[owner].orEmpty()
        // What every episode of this owner would carry with no rows anywhere:
        // the owner's tags, minus the ones that cover the whole host and so say
        // nothing. This is what a row is written against.
        val default = inherited.filterNot { it.lowercase() in hiddenByDefault }
        episodeIds.forEach { episodeId ->
            val ownRows = rowsByEpisode[episodeId].orEmpty()
            val placed = ownRows.filter { it.visible }.map { it.tag }
            val removed = ownRows.filterNot { it.visible }.mapTo(HashSet()) { it.tag.lowercase() }
            placed.forEach { tag ->
                counts[tag.lowercase()] = (counts[tag.lowercase()] ?: 0) + 1
            }
            val kept = inherited.filterNot { tag ->
                val key = tag.lowercase()
                key in overridden || key in hiddenByDefault || key in removed
            }
            // Placed first: the user's own tags lead on the episode they are on.
            byEpisodeId[episodeId] = placed + kept
            defaultByEpisodeId[episodeId] = default
        }
    }

    // Tags that exist only because the user put them on episodes, most-tagged
    // first - where they sit in the entry's tag row.
    val inheritedKeys = episodesByOwner.keys
        .flatMap { ownerTags[it].orEmpty() }
        .mapTo(HashSet()) { it.lowercase() }
    val episodeOnly = rows
        .filter { it.visible && it.tag.lowercase() !in inheritedKeys }
        .distinctBy { it.tag.lowercase() }
        .sortedByDescending { counts[it.tag.lowercase()] ?: 0 }
        .map { it.tag }

    return EpisodeTagResolution(
        byEpisodeId = byEpisodeId,
        counts = counts,
        hiddenByDefault = hiddenByDefault,
        episodeOnly = episodeOnly,
        defaultByEpisodeId = defaultByEpisodeId,
    )
}
// <-- AM (EPISODE_TAGS)
