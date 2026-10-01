// AM (NAMED_SEASONS) -->
package aniyomi.domain.season.model

/**
 * One season of a library entry. [number] is its stable identity (see
 * entry_seasons.sq); [name] null means unnamed, labelled by display position.
 * [sortOrder] null means the season has no stored row yet.
 */
data class EntrySeason(
    val number: Long,
    val name: String?,
    val sortOrder: Long?,
)

/**
 * A season a picker offers but that does not exist yet - picking it is what
 * creates it, so a new season needs no trip to the season manager.
 *
 * [labelPosition] is the 1-based display position it will occupy, which is what
 * it is labelled by, as with every unnamed season. There is deliberately no
 * number here: the number is assigned by ManageEntrySeasons.create, which for
 * the merge picker only runs when the dialog is saved.
 */
data class OfferedSeason(val labelPosition: Int)

/** The one "next season" a picker offers, given the seasons it is showing. */
fun offeredNextSeason(shownSeasons: List<EntrySeason>): OfferedSeason =
    OfferedSeason(labelPosition = shownSeasons.size + 1)

/**
 * Stand-in number for the [index]-th (0-based) season staged for creation but
 * not yet created, used by pickers that stage their edits.
 *
 * Negative because real numbers are always positive, so a provisional number
 * that reaches a write by mistake cannot silently land on a real season.
 */
fun provisionalSeasonNumber(index: Int): Long = -(index + 1).toLong()

/** True for a [provisionalSeasonNumber] - a season that has not been created yet. */
fun Long.isProvisionalSeason(): Boolean = this < 0
// <-- AM (NAMED_SEASONS)
