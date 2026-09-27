// AM (TAG_LIMIT) -->
package animiru.domain.anime.model

/**
 * An entry's tags split into the ones shown inline and the ones kept behind the
 * "more" chip, each in the entry's own tag order.
 *
 * The split is a display concern only - nothing is dropped from the entry - so
 * [hidden] stays whole for the popout, and tag search still matches tags the
 * split leaves out.
 *
 * [from] is the single place that decides which of an entry's tags are shown.
 */
data class DisplayedTags(
    /** Shown inline, in the entry's tag order. */
    val visible: List<String>,
    /** Only in the popout, in the entry's tag order. */
    val hidden: List<String>,
    /**
     * The lowercased tags the user pinned, whichever list they landed in - what
     * tells a tag the user placed from one the cap merely let through, which the
     * popout shows and nothing else can infer from [visible] and [hidden] alone.
     *
     * Pins for tags the entry no longer has are left out: they change nothing on
     * screen, so they should not mark the entry as customised either.
     */
    val pinned: Set<String> = emptySet(),
) {
    /** Whether anything here is the user's choice rather than the cap's. */
    val isCustomised: Boolean = pinned.isNotEmpty()

    /** Whether a "more" chip is needed at all. */
    val hasHidden: Boolean = hidden.isNotEmpty()

    companion object {
        val Empty = DisplayedTags(visible = emptyList(), hidden = emptyList())

        /**
         * Splits [tags] into what the entry shows inline and what the popout keeps.
         *
         * [overrides] maps a lowercased tag to whether the user pinned it to
         * Visible or Hidden, and wins outright and permanently: a pinned tag stays
         * where the user put it when [maxShown] later changes, and only clearing
         * the entry's choices hands it back.
         *
         * A tag pinned to Visible takes up one of [maxShown]'s slots, so the row
         * stays the size the setting asks for instead of growing by one per pin -
         * pinning promotes a tag ahead of the unpinned ones rather than adding to
         * them. Pinning more than [maxShown] tags does overrun it: a pin is an
         * instruction, not a request.
         *
         * Pinning to Hidden frees no slot - it shrinks the row. An unpinned tag has
         * to clear two bars to show: it must sit in the entry's first [maxShown]
         * tags, and a slot must still be free. The first bar is what stops a tag
         * appearing merely because a different one was hidden, which is not what
         * hiding one thing asks for; the second is what makes a pin cost a slot.
         *
         * So with a cap of three over A B C D E F: nothing pinned shows A B C;
         * pinning F to Visible shows A B F, since F's slot is the one C was using;
         * pinning A to Hidden shows B C, because D is outside the first three.
         *
         * [maxShown] is a plain count of slots, or null for no cap at all. Zero
         * shows no unpinned tag - the pinned ones still show, since a pin outranks
         * the count. Null rather than a sentinel keeps the sentinel's value a
         * setting's business, not this split's.
         */
        fun from(
            tags: List<String>?,
            maxShown: Int?,
            overrides: Map<String, Boolean> = emptyMap(),
        ): DisplayedTags {
            val all = tags.orEmpty()
            if (all.isEmpty()) return Empty
            val pins = all.map { overrides[it.lowercase()] }
            var budget = maxShown?.minus(pins.count { it == true })?.coerceAtLeast(0) ?: 0
            val visible = mutableListOf<String>()
            val hidden = mutableListOf<String>()
            all.forEachIndexed { index, tag ->
                when {
                    pins[index] == true -> visible += tag
                    pins[index] == false -> hidden += tag
                    maxShown == null -> visible += tag
                    index < maxShown && budget > 0 -> {
                        visible += tag
                        budget--
                    }
                    else -> hidden += tag
                }
            }
            return DisplayedTags(
                visible = visible,
                hidden = hidden,
                pinned = all.filterIndexed { index, _ -> pins[index] != null }
                    .mapTo(mutableSetOf(), String::lowercase),
            )
        }
    }
}
// <-- AM (TAG_LIMIT)
