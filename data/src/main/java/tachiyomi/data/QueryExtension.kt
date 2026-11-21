package tachiyomi.data

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import kotlin.coroutines.CoroutineContext

// AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES) -->
/**
 * Where a database read actually executes, and the default was the caller.
 *
 * The driver is AndroidxSqliteDriver over BundledSQLiteDriver, which is
 * synchronous: a statement runs on whichever thread reaches it. SQLDelight's
 * async generation makes the generated functions `suspend`, but suspend is not
 * a dispatcher, so `awaitAsList()` on the main thread is a main-thread query
 * with a coroutine wrapper around it.
 *
 * Both halves of the data layer had that default:
 *
 *  - The flow reads below passed [kotlin.coroutines.EmptyCoroutineContext] to
 *    mapToList, whose `context` parameter is precisely the context the query
 *    runs in. Empty means the collector's, and these are collected from
 *    viewModelScope, which is Dispatchers.Main.immediate - so every one of
 *    them read SQLite on the UI thread. Every call site in this module used
 *    the default, so one value here moves all of them.
 *  - The suspend reads called the generated awaitAs* directly from repository
 *    functions that are themselves called out of viewModelScope.
 *
 * [awaitList], [awaitOne] and [awaitOneOrNull] exist so the dispatch belongs to
 * the READ rather than to whoever remembered to wrap it. A repository function
 * added later that forgets a withIOContext still runs off the main thread,
 * which a convention held only at call sites cannot promise. Nesting is free
 * where a caller already wrapped its body: withContext to the dispatcher it is
 * already on does not re-dispatch.
 *
 * NOT FIXED AT THE DRIVER, which would be the one true place for it. Wrapping
 * SqlDriver so every execute returns a QueryResult.AsyncValue over
 * Dispatchers.IO would cover generated mutators and transactions too - but a
 * transaction would then span several IO threads while holding one connection,
 * and whether that driver's connection handling tolerates that is not
 * something this repository can show. An unverifiable fix in the lowest layer
 * is worse than a verifiable one in the layer above it.
 */
fun <T : Any> Query<T>.subscribeToList(
    context: CoroutineContext = Dispatchers.IO,
): Flow<List<T>> = asFlow().mapToList(context)

fun <T : Any> Query<T>.subscribeToOne(
    context: CoroutineContext = Dispatchers.IO,
): Flow<T> = asFlow().mapToOne(context)

fun <T : Any> Query<T>.subscribeToOneOrNull(
    context: CoroutineContext = Dispatchers.IO,
): Flow<T?> = asFlow().mapToOneOrNull(context)

/*
 * On ExecutableQuery, not Query, which is what SQLDelight declares awaitAs* on.
 * Query extends it, so a receiver of Query covers only the queries that can
 * also be observed as a flow and misses the grouped and mapped ones.
 */

/** [awaitAsList] off the caller's thread - see the note above. */
suspend fun <T : Any> ExecutableQuery<T>.awaitList(): List<T> = withIOContext { awaitAsList() }

/** [awaitAsOne] off the caller's thread - see the note above. */
suspend fun <T : Any> ExecutableQuery<T>.awaitOne(): T = withIOContext { awaitAsOne() }

/** [awaitAsOneOrNull] off the caller's thread - see the note above. */
suspend fun <T : Any> ExecutableQuery<T>.awaitOneOrNull(): T? = withIOContext { awaitAsOneOrNull() }
// <-- AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES)
