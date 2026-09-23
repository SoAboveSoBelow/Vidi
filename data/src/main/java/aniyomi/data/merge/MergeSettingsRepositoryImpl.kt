// AM (MERGE_SETTINGS) -->
package aniyomi.data.merge

import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.merge.model.MergeSettings
import aniyomi.domain.merge.repository.MergeSettingsRepository
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergeSettingsRepositoryImpl(
    private val database: Database,
) : MergeSettingsRepository {

    override suspend fun get(mergeParentId: Long): MergeSettings {
        return database.merge_settingsQueries.getByMergeParentId(mergeParentId, ::mapSettings)
            .awaitAsOneOrNull() ?: MergeSettings()
    }

    override fun getAsFlow(mergeParentId: Long): Flow<MergeSettings> {
        return database.merge_settingsQueries.getByMergeParentId(mergeParentId, ::mapSettings)
            .subscribeToList()
            .map { it.firstOrNull() ?: MergeSettings() }
    }

    override suspend fun set(mergeParentId: Long, settings: MergeSettings) {
        database.merge_settingsQueries.upsert(mergeParentId, settings.dedupeMode.value, settings.infoAnimeId)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapSettings(mergeParentId: Long, dedupeMode: Long, infoAnimeId: Long?): MergeSettings {
        return MergeSettings(dedupeMode = DedupeMode.from(dedupeMode), infoAnimeId = infoAnimeId)
    }
}
// <-- AM (MERGE_SETTINGS)
