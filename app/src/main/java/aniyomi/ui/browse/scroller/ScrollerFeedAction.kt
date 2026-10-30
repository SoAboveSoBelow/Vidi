package aniyomi.ui.browse.scroller

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.anime.model.Anime

// AM (BROWSE_SCROLLER) -->
/** A source-declared remote action shown on a feed card. */
data class ScrollerFeedAction(
    val id: String,
    val icon: String,
    val label: String,
    val initiallyActive: Boolean,
) {
    companion object {
        fun from(anime: Anime): List<ScrollerFeedAction> {
            val actions = anime.memo["feed"]
                ?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.get("actions")
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: return emptyList()

            return actions.mapNotNull { element ->
                val action = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = action["id"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val icon = action["icon"]?.stringOrNull()
                if (icon != "favorite" && icon != "bookmark") return@mapNotNull null
                ScrollerFeedAction(
                    id = id,
                    icon = icon,
                    label = action["label"]?.stringOrNull().orEmpty(),
                    initiallyActive = action["active"]
                        ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
                        ?: false,
                )
            }
        }

        private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            runCatching { jsonPrimitive.content }.getOrNull()
    }
}
// <-- AM (BROWSE_SCROLLER)
