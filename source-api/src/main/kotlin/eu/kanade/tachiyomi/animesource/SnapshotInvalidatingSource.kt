package eu.kanade.tachiyomi.animesource

import kotlinx.coroutines.flow.Flow

/**
 * A source whose browse listings are served from a persisted snapshot rather
 * than read live on every call.
 *
 * The source owns refreshing: it re-reads whatever backs the listing in the
 * background and emits [snapshotRefreshes] whenever the snapshot changed.
 * Holders of stale pages (the source paging source) collect this and
 * invalidate themselves, so the UI moves to the fresh snapshot without the
 * user pulling to refresh.
 *
 * This exists for sources where a live read is too slow to sit on the user's
 * critical path - the local source, where every listing is a storage
 * provider query. Disk remains the source of truth; the snapshot only
 * decides the first frame.
 */
interface SnapshotInvalidatingSource {
    val snapshotRefreshes: Flow<Unit>
}
