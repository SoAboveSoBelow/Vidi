package tachiyomi.domain.history.model

// AM (HISTORY_ORIGIN) -->
/**
 * What produced a history entry.
 *
 * History is one row per episode, so a card you scrolled past and an episode
 * you sat down and watched would otherwise be the same record - and the feed,
 * which passes over far more entries than anyone watches, would bury real
 * watches under them. Recording which of the two wrote a row lets the history
 * screen show one, the other, or both, instead of the feed having to not
 * record at all.
 */
enum class HistoryOrigin(val value: Long) {
    /** Watched in the player - what every row before this existed as. */
    Player(0L),

    /** Seen on a card in the browse feed. */
    Feed(1L),
    ;

    companion object {
        fun from(value: Long): HistoryOrigin = entries.firstOrNull { it.value == value } ?: Player
    }
}
// <-- AM (HISTORY_ORIGIN)
