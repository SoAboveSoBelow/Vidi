package tachiyomi.data.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitOneOrNull
import tachiyomi.data.subscribeToList
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class StubSourceRepositoryImpl(
    private val database: Database,
) : StubSourceRepository {

    override fun subscribeAll(): Flow<List<StubSource>> {
        return database.sourcesQueries.findAll(::mapStubSource).subscribeToList()
    }

    override suspend fun getStubSource(id: Long): StubSource? {
        return database.sourcesQueries.findOne(id, ::mapStubSource).awaitOneOrNull()
    }

    override suspend fun upsertStubSource(id: Long, lang: String, name: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.sourcesQueries.upsert(id, lang, name)
        }
    }

    private fun mapStubSource(
        id: Long,
        lang: String,
        name: String,
    ): StubSource = StubSource(id = id, lang = lang, name = name)
}
