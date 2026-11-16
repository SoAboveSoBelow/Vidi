// AY -->
package tachiyomi.domain.custombutton.model

import androidx.compose.runtime.Stable

@Stable
data class CustomButton(
    val id: Long,
    val name: String,
    val isFavorite: Boolean,
    val sortIndex: Long,
    val content: String,
    val doubleTapContent: String = "",
    val longPressContent: String,
    val onStartup: String,
    val icon: String? = null,
    /** Renders as a switch; Lua reports its checked state through set_button_toggle. */
    val isToggle: Boolean = false,
) {
    fun getButtonContent(primaryId: Long): String {
        val isPrimary = if (primaryId == id) "true" else "false"
        return content.replace($$"$id", id.toString()).replace($$"$isPrimary", isPrimary)
    }

    fun getButtonDoubleTapContent(primaryId: Long): String {
        val isPrimary = if (primaryId == id) "true" else "false"
        return doubleTapContent.replace($$"$id", id.toString()).replace($$"$isPrimary", isPrimary)
    }

    fun getButtonLongPressContent(primaryId: Long): String {
        val isPrimary = if (primaryId == id) "true" else "false"
        return longPressContent.replace($$"$id", id.toString()).replace($$"$isPrimary", isPrimary)
    }

    fun getButtonOnStartup(primaryId: Long): String {
        val isPrimary = if (primaryId == id) "true" else "false"
        return onStartup.replace($$"$id", id.toString()).replace($$"$isPrimary", isPrimary)
    }
}
// <-- AY
