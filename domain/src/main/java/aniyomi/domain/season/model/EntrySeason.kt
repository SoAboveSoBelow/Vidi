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
// <-- AM (NAMED_SEASONS)
