// AY -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.custombutton.model.CustomButton

@Serializable
class BackupCustomButtons(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var isFavorite: Boolean,
    @ProtoNumber(3) var sortIndex: Long,
    @ProtoNumber(4) var content: String,
    @ProtoNumber(5) var longPressContent: String,
    @ProtoNumber(6) var onStartup: String,
    @ProtoNumber(7) var doubleTapContent: String = "",
    @ProtoNumber(8) var icon: String? = null,
    @ProtoNumber(9) var isToggle: Boolean = false,
)

val backupCustomButtonsMapper = { btn: CustomButton ->
    BackupCustomButtons(
        name = btn.name,
        isFavorite = btn.isFavorite,
        sortIndex = btn.sortIndex,
        content = btn.content,
        doubleTapContent = btn.doubleTapContent,
        longPressContent = btn.longPressContent,
        onStartup = btn.onStartup,
        icon = btn.icon,
        isToggle = btn.isToggle,
    )
}
// <-- AY
