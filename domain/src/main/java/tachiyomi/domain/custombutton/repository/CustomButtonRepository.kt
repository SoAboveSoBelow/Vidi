// AY -->
package tachiyomi.domain.custombutton.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.domain.custombutton.model.CustomButtonUpdate

interface CustomButtonRepository {

    fun subscribeAll(): Flow<List<CustomButton>>

    suspend fun getAll(): List<CustomButton>

    suspend fun insertCustomButton(
        name: String,
        sortIndex: Long,
        content: String,
        doubleTapContent: String,
        longPressContent: String,
        onStartup: String,
        icon: String?,
        isToggle: Boolean,
    )

    suspend fun updatePartialCustomButton(update: CustomButtonUpdate)

    suspend fun updatePartialCustomButtons(updates: List<CustomButtonUpdate>)

    suspend fun deleteCustomButton(customButtonId: Long)

    /** Reinserts missing built-in rows without overwriting the user's edited copies. */
    suspend fun restoreDefaultCustomButtons()
}
// <-- AY
