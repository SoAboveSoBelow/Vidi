package eu.kanade.domain.source.interactor

import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.source.local.isLocal
import java.util.Collections

/**
 * Sources with how much of the library they supply.
 *
 * Counts every entry the source contributes, not just favorited ones: a
 * source whose entries only live inside merged entries still has library
 * content to migrate.
 */
@Inject
class GetSourcesWithLibraryCount(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<List<Pair<Source, Long>>> {
        return combine(
            preferences.migrationSortingDirection.changes(),
            preferences.migrationSortingMode.changes(),
            repository.getSourcesWithLibraryCount(),
        ) { direction, mode, list ->
            list
                .filterNot { it.first.isLocal() }
                // AM (MERGED_SOURCES): the merge pseudo-source is not a
                // catalogue and cannot be searched, so a merged entry is
                // never a migration target or subject. Its sources are,
                // and they are listed under their own extensions.
                .filterNot { it.first.id == MERGED_SOURCE_ID }
                .sortedWith(sortFn(direction, mode))
        }
    }

    private fun sortFn(
        direction: SetMigrateSorting.Direction,
        sorting: SetMigrateSorting.Mode,
    ): java.util.Comparator<Pair<Source, Long>> {
        val sortFn: (Pair<Source, Long>, Pair<Source, Long>) -> Int = { a, b ->
            when (sorting) {
                SetMigrateSorting.Mode.ALPHABETICAL -> {
                    when {
                        a.first.isStub && !b.first.isStub -> -1
                        b.first.isStub && !a.first.isStub -> 1
                        else -> a.first.name.lowercase().compareToWithCollator(b.first.name.lowercase())
                    }
                }
                SetMigrateSorting.Mode.TOTAL -> {
                    when {
                        a.first.isStub && !b.first.isStub -> -1
                        b.first.isStub && !a.first.isStub -> 1
                        else -> a.second.compareTo(b.second)
                    }
                }
            }
        }

        return when (direction) {
            SetMigrateSorting.Direction.ASCENDING -> Comparator(sortFn)
            SetMigrateSorting.Direction.DESCENDING -> Collections.reverseOrder(sortFn)
        }
    }
}
