package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import tachiyomi.domain.source.service.SourceManager

@Inject
class SourcesBackupCreator(
    private val sourceManager: SourceManager,
) {

    operator fun invoke(animes: List<BackupAnime>): List<BackupSource> {
        return invoke(animes.asSequence().map(BackupAnime::source).distinct().toSet())
    }

    // AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME): the entries are streamed out
    // one at a time and not kept, so the caller collects their source ids as it
    // goes and hands over that set instead of the entry list.
    operator fun invoke(sourceIds: Set<Long>): List<BackupSource> {
        return sourceIds
            .map(sourceManager::getOrStub)
            .map { it.toBackupSource() }
    }
}

private fun AnimeSource.toBackupSource() =
    BackupSource(
        name = this.name,
        sourceId = this.id,
    )
