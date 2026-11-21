package eu.kanade.domain.source.service

import aniyomi.domain.source.model.BrowseDisplayMode
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
// AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
import tachiyomi.domain.source.model.SourcePageCoverage

@Inject
@SingleIn(AppScope::class)
class SourcePreferences(
    // AM (BROWSE_SCROLLER): held so the per-source display mode override can
    // be looked up by source id at call time.
    private val preferenceStore: PreferenceStore,
) {

    // AM (BROWSE_SCROLLER): type widened from LibraryDisplayMode to
    // BrowseDisplayMode. The key and the stored strings are unchanged, so an
    // existing value still deserializes to the same layout.
    val sourceDisplayMode: Preference<BrowseDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        BrowseDisplayMode.default,
        BrowseDisplayMode.Serializer::serialize,
        BrowseDisplayMode.Serializer::deserialize,
    )

    // AM (BROWSE_SCROLLER) -->
    /**
     * Per-source override of [sourceDisplayMode]. Empty means "inherit", which
     * is what every source starts out as, so changing the layout for one
     * source leaves the rest alone.
     */
    fun sourceDisplayModeOverride(sourceId: Long): Preference<String> =
        preferenceStore.getString("pref_display_mode_catalogue_$sourceId", "")
    // <-- AM (BROWSE_SCROLLER)

    // AM (BROWSE_HIDE_VIEWED) -->
    /**
     * Whether a source's browse listing drops entries that have already been
     * viewed - opened in the player or played as a card in the scroller feed,
     * which is not the same as marked seen.
     *
     * Per source, and persisted, so a source that was left with it on opens
     * with its first page already trimmed instead of needing the toggle and a
     * reload. Keyed like [sourceDisplayModeOverride] above.
     */
    fun hideViewedItems(sourceId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("browse_hide_viewed_items_$sourceId", false)
    // <-- AM (BROWSE_HIDE_VIEWED)

    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS) -->
    /**
     * How much of a listing page must already have been viewed before
     * hide-viewed passes the page over instead of requesting it.
     *
     * Not per source, unlike the toggle above: it is a statement about how much
     * unviewed content the user is willing to miss in exchange for fewer
     * requests, which is the same willingness on every source. See
     * SourcePageCoverage.COVERED_FRACTION for the default and for what moving
     * it costs in each direction, and PlayerPreferences.historyThresholdSeconds
     * for the setting it has to be read alongside - a page's viewed fraction is
     * only as high as that threshold lets it get.
     *
     * At 1.0 a page is skipped only when every entry on it was viewed, which is
     * the conservative end rather than off; the toggle is what turns skipping
     * off, because with the trim off nothing may be skipped at all.
     */
    val browsePageSkipThreshold: Preference<Float> = preferenceStore.getFloat(
        "browse_page_skip_threshold",
        SourcePageCoverage.COVERED_FRACTION,
    )
    // <-- AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val showNsfwSource: Preference<Boolean> = preferenceStore.getBoolean("show_nsfw_source", true)

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    val extensionRepos: Preference<Set<String>> = preferenceStore.getStringSet("extension_repos", emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByEpisodes: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_episodes",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )
}
