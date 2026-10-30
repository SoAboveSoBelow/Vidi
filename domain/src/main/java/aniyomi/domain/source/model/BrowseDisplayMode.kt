package aniyomi.domain.source.model

import tachiyomi.domain.library.model.LibraryDisplayMode

// AM (BROWSE_SCROLLER) -->
/**
 * How a source's browse screen lays its results out.
 *
 * Wraps [LibraryDisplayMode] rather than extending it: the scroller is a
 * browse-only mode, and the saved library has no rendering for it. Keeping it
 * out of [LibraryDisplayMode] makes that impossible to get wrong instead of
 * relying on every library `when` to filter it back out.
 *
 * Serialization is backward compatible with the plain [LibraryDisplayMode]
 * strings the browse preference stored before this existed.
 */
sealed interface BrowseDisplayMode {

    data class Standard(val mode: LibraryDisplayMode) : BrowseDisplayMode

    data object Scroller : BrowseDisplayMode

    /**
     * The grid/list mode to fall back to on screens that cannot scroll
     * (migration target pickers, for one).
     */
    val standard: LibraryDisplayMode
        get() = when (this) {
            is Standard -> mode
            Scroller -> LibraryDisplayMode.default
        }

    object Serializer {
        fun deserialize(serialized: String): BrowseDisplayMode = BrowseDisplayMode.deserialize(serialized)

        fun serialize(value: BrowseDisplayMode): String = value.serialize()
    }

    companion object {
        const val SCROLLER = "SCROLLER"

        val default = Standard(LibraryDisplayMode.default)

        fun deserialize(serialized: String): BrowseDisplayMode = when (serialized) {
            SCROLLER -> Scroller
            // Unknown values fall through to LibraryDisplayMode's own default.
            else -> Standard(LibraryDisplayMode.deserialize(serialized))
        }
    }

    fun serialize(): String = when (this) {
        is Standard -> mode.serialize()
        Scroller -> SCROLLER
    }
}
// <-- AM (BROWSE_SCROLLER)
