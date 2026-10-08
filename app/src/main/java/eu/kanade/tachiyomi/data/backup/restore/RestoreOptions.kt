package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    // AY -->
    val customButtons: Boolean = true,
    // <-- AY
    val sourceSettings: Boolean = true,
    // AY -->
    val extensions: Boolean = false,
    // <-- AY
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        // AY -->
        customButtons,
        // <-- AY
        sourceSettings,
        // AY -->
        extensions,
        // <-- AY
    )

    fun canRestore() = libraryEntries ||
        categories ||
        appSettings ||
        extensionStores ||
        // AY -->
        customButtons ||
        // <-- AY
        sourceSettings ||
        // AY -->
        extensions
    // <-- AY

    companion object {
        val options = listOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = RestoreOptions::extensionStores,
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            // AY -->
            Entry(
                label = AYMR.strings.custom_button_settings,
                getter = RestoreOptions::customButtons,
                setter = { options, enabled -> options.copy(customButtons = enabled) },
            ),
            // <-- AY
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            // AY -->
            Entry(
                label = MR.strings.label_extensions,
                getter = RestoreOptions::extensions,
                setter = { options, enabled -> options.copy(extensions = enabled) },
            ),
            // <-- AY
        )

        // Indexed with a default rather than directly: a job queued by an older
        // build carries a shorter array, and bare indexing would crash on upgrade
        // instead of falling back for the options that build did not know about.
        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array.optionAt(0, true),
            categories = array.optionAt(1, true),
            appSettings = array.optionAt(2, true),
            extensionStores = array.optionAt(3, true),
            // AY -->
            customButtons = array.optionAt(4, true),
            // <-- AY
            sourceSettings = array.optionAt(5, true),
            // AY -->
            extensions = array.optionAt(6, false),
            // <-- AY
        )

        // AM -->
        private fun BooleanArray.optionAt(index: Int, default: Boolean): Boolean {
            return if (index in indices) this[index] else default
        }
        // <-- AM
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
