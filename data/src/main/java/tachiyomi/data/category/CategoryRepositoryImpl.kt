package tachiyomi.data.category

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitList
import tachiyomi.data.awaitOneOrNull
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries.getCategory(id, ::mapCategory).awaitOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoriesQueries.getCategories(::mapCategory).awaitList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries.getCategories(::mapCategory).subscribeToList()
    }

    override suspend fun getCategoriesByAnimeId(animeId: Long): List<Category> {
        return database.categoriesQueries.getCategoriesByAnimeId(animeId, ::mapCategory).awaitList()
    }

    override fun getCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> {
        return database.categoriesQueries.getCategoriesByAnimeId(animeId, ::mapCategory).subscribeToList()
    }

    // AY -->
    override suspend fun getAllVisible(): List<Category> {
        return database.categoriesQueries.getVisibleCategories(::mapCategory).awaitList()
    }

    override fun getAllVisibleAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries.getVisibleCategories(::mapCategory).subscribeToList()
    }

    override suspend fun getVisibleCategoriesByAnimeId(animeId: Long): List<Category> {
        return database.categoriesQueries.getVisibleCategoriesByAnimeId(animeId, ::mapCategory).awaitList()
    }

    override fun getVisibleCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> {
        return database.categoriesQueries.getVisibleCategoriesByAnimeId(animeId, ::mapCategory).subscribeToList()
    }
    // <-- AY

    override suspend fun insert(category: Category) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.insert(
                name = category.name,
                order = category.order,
                flags = category.flags,
            )
        }
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.updateName(name = name, categoryId = categoryId)
        }
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.updateFlags(flags = flags, categoryId = categoryId)
        }
    }

    // AY -->
    override suspend fun updateHidden(categoryId: Long, hidden: Boolean) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.updateHidden(hidden = if (hidden) 1 else 0, categoryId = categoryId)
        }
    }
    // <-- AY

    override suspend fun updateAllFlags(flags: Long?) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.updateAllFlags(flags = flags)
        }
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a bulk write, and the
        // whole transaction on one IO thread rather than each statement on
        // the caller's - nothing inside suspends at a dispatch point, so the
        // body does not move between threads mid-transaction.
        withIOContext {
            database.transaction {
                orderedIds.forEachIndexed { index, categoryId ->
                    database.categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
                }
            }
        }
    }

    override suspend fun delete(categoryId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.categoriesQueries.delete(categoryId = categoryId)
        }
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
        // AY -->
        hidden: Long,
        // <-- AY
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
            // AY -->
            hidden = hidden == 1L,
            // <-- AY
        )
    }
}
