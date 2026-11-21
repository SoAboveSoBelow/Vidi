// AY -->
package tachiyomi.data.custombutton

import android.database.sqlite.SQLiteException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitList
import tachiyomi.data.subscribeToList
import tachiyomi.domain.custombutton.exception.SaveCustomButtonException
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.domain.custombutton.model.CustomButtonUpdate
import tachiyomi.domain.custombutton.repository.CustomButtonRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CustomButtonRepositoryImpl(
    private val database: Database,
) : CustomButtonRepository {
    override fun subscribeAll(): Flow<List<CustomButton>> {
        return database.custom_buttonsQueries.findAll(::mapCustomButton).subscribeToList()
    }

    override suspend fun getAll(): List<CustomButton> {
        return database.custom_buttonsQueries.findAll(::mapCustomButton).awaitList()
    }

    override suspend fun insertCustomButton(
        name: String,
        sortIndex: Long,
        content: String,
        doubleTapContent: String,
        longPressContent: String,
        onStartup: String,
        icon: String?,
        isToggle: Boolean,
    ) {
        try {
            withIOContext {
                database.custom_buttonsQueries.insert(
                    name,
                    false,
                    sortIndex,
                    content,
                    doubleTapContent,
                    longPressContent,
                    onStartup,
                    icon,
                    isToggle,
                )
            }
        } catch (ex: SQLiteException) {
            throw SaveCustomButtonException(ex)
        }
    }

    override suspend fun updatePartialCustomButton(update: CustomButtonUpdate) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.custom_buttonsQueries.update(
                name = update.name,
                isFavorite = update.isFavorite,
                sortIndex = update.sortIndex,
                content = update.content,
                doubleTapContent = update.doubleTapContent,
                longPressContent = update.longPressContent,
                customButtonId = update.id,
                onStartup = update.onStartup,
                icon = update.icon,
                isToggle = update.isToggle,
            )
        }
    }

    override suspend fun updatePartialCustomButtons(updates: List<CustomButtonUpdate>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a bulk write, and the
        // whole transaction on one IO thread rather than each statement on
        // the caller's - nothing inside suspends at a dispatch point, so the
        // body does not move between threads mid-transaction.
        withIOContext {
            database.transaction {
                updates.forEach { updatePartialCustomButton(it) }
            }
        }
    }

    override suspend fun deleteCustomButton(customButtonId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.custom_buttonsQueries.delete(customButtonId)
        }
    }

    override suspend fun restoreDefaultCustomButtons() {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.custom_buttonsQueries.restoreDefaults()
        }
    }

    private fun mapCustomButton(
        id: Long,
        name: String,
        isFavorite: Boolean,
        sortIndex: Long,
        content: String,
        doubleTapContent: String,
        longPressContent: String,
        onStartup: String,
        icon: String?,
        isToggle: Boolean,
    ): CustomButton = CustomButton(
        id = id,
        name = name,
        isFavorite = isFavorite,
        sortIndex = sortIndex,
        content = content,
        doubleTapContent = doubleTapContent,
        longPressContent = longPressContent,
        onStartup = onStartup,
        icon = icon,
        isToggle = isToggle,
    )
}
// <-- AY
