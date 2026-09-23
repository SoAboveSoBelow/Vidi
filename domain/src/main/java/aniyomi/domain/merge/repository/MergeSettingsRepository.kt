// AM (MERGE_SETTINGS) -->
package aniyomi.domain.merge.repository

import aniyomi.domain.merge.model.MergeSettings
import kotlinx.coroutines.flow.Flow

interface MergeSettingsRepository {

    /** The merge's settings, or the defaults if it has never been configured. */
    suspend fun get(mergeParentId: Long): MergeSettings

    fun getAsFlow(mergeParentId: Long): Flow<MergeSettings>

    suspend fun set(mergeParentId: Long, settings: MergeSettings)
}
// <-- AM (MERGE_SETTINGS)
