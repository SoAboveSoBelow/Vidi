package aniyomi.source.js

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a project declares about one filter.
 *
 * Declarative rather than constructed: a project says "a dropdown called Order
 * with these labels", and the host builds the real [AnimeFilter] and renders it
 * with the app's own filter sheet. The alternative - letting project code build
 * UI - would mean exposing the filter classes across the boundary, which the
 * QuickJS binding cannot carry and which would tie every project to the app's
 * internals.
 *
 * [keys] is the part that matters in practice. The sheet shows [values] and the
 * project is handed the matching [keys] entry, so a label can be "Most viewed"
 * while the project receives `video_viewed` and never has to map one to the
 * other. Without keys the label is handed back as-is.
 */
@Serializable
class JsFilterSpec(
    val type: String,
    val name: String = "",
    val values: List<String> = emptyList(),
    val keys: List<String> = emptyList(),
    /** Children, for `group`. One level deep; a group inside a group is ignored. */
    val filters: List<JsFilterSpec> = emptyList(),
    /** Initial selection for `select` and `sort`. */
    val default: Int = 0,
    /** Initial state for `checkbox`. */
    val checked: Boolean = false,
) {
    companion object {
        const val SELECT = "select"
        const val TEXT = "text"
        const val CHECKBOX = "checkbox"
        const val TRISTATE = "tristate"
        const val SORT = "sort"
        const val GROUP = "group"
        const val HEADER = "header"
        const val SEPARATOR = "separator"
    }
}

/**
 * The concrete filters, since every [AnimeFilter] subclass worth using is
 * abstract. Each carries the project's [keys] so the state can be reported back
 * in the project's own vocabulary rather than the sheet's labels.
 */
class JsSelectFilter(name: String, values: Array<String>, val keys: List<String>, state: Int) :
    AnimeFilter.Select<String>(name, values, state)

class JsTextFilter(name: String) : AnimeFilter.Text(name)

class JsCheckBoxFilter(name: String, state: Boolean) : AnimeFilter.CheckBox(name, state)

class JsTriStateFilter(name: String) : AnimeFilter.TriState(name)

class JsSortFilter(name: String, values: Array<String>, val keys: List<String>) :
    AnimeFilter.Sort(name, values, Selection(0, false))

class JsGroupFilter(name: String, state: List<AnimeFilter<*>>) :
    AnimeFilter.Group<AnimeFilter<*>>(name, state)

class JsHeaderFilter(name: String) : AnimeFilter.Header(name)

class JsSeparatorFilter : AnimeFilter.Separator()

/** Builds the app's filter list from what the project declared. */
fun List<JsFilterSpec>.toAnimeFilterList(): AnimeFilterList =
    AnimeFilterList(mapNotNull { it.toAnimeFilter(allowGroup = true) })

private fun JsFilterSpec.toAnimeFilter(allowGroup: Boolean): AnimeFilter<*>? = when (type) {
    JsFilterSpec.SELECT -> JsSelectFilter(
        name = name,
        values = values.toTypedArray(),
        keys = keys,
        state = default.coerceIn(0, (values.size - 1).coerceAtLeast(0)),
    ).takeIf { values.isNotEmpty() }

    JsFilterSpec.SORT -> JsSortFilter(name, values.toTypedArray(), keys)
        .takeIf { values.isNotEmpty() }

    JsFilterSpec.TEXT -> JsTextFilter(name)
    JsFilterSpec.CHECKBOX -> JsCheckBoxFilter(name, checked)
    JsFilterSpec.TRISTATE -> JsTriStateFilter(name)
    JsFilterSpec.HEADER -> JsHeaderFilter(name)
    JsFilterSpec.SEPARATOR -> JsSeparatorFilter()

    // Nested groups are dropped rather than flattened: the filter sheet renders
    // one level, and silently flattening would move a person's checkbox
    // somewhere they did not put it.
    JsFilterSpec.GROUP -> if (!allowGroup) {
        null
    } else {
        JsGroupFilter(name, filters.mapNotNull { it.toAnimeFilter(allowGroup = false) })
    }

    // An unknown type is the project's mistake, and dropping it quietly is
    // kinder than refusing to show any filters at all.
    else -> null
}

/**
 * The selected state, in the project's vocabulary.
 *
 * Keyed by name so project code reads `args.filters['Order']` instead of
 * walking an array and matching strings. Headers and separators are omitted -
 * they hold no state - and anything the project did not declare cannot appear,
 * since this only ever serializes filters the host built from a spec.
 */
fun AnimeFilterList.toJsonObject(): JsonObject = buildJsonObject {
    forEach { filter -> putFilter(filter) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFilter(filter: AnimeFilter<*>) {
    when (filter) {
        is JsSelectFilter -> put(filter.name, JsonPrimitive(filter.selectedKey()))
        is JsSortFilter -> put(
            filter.name,
            buildJsonObject {
                val selection = filter.state
                put("key", filter.keyAt(selection?.index ?: 0))
                put("ascending", selection?.ascending == true)
            },
        )
        is JsTextFilter -> put(filter.name, JsonPrimitive(filter.state))
        is JsCheckBoxFilter -> put(filter.name, JsonPrimitive(filter.state))
        is JsTriStateFilter -> put(
            filter.name,
            JsonPrimitive(
                when (filter.state) {
                    AnimeFilter.TriState.STATE_INCLUDE -> "include"
                    AnimeFilter.TriState.STATE_EXCLUDE -> "exclude"
                    else -> "ignore"
                },
            ),
        )
        is JsGroupFilter -> put(
            filter.name,
            buildJsonObject {
                filter.state.forEach { child -> putFilter(child) }
            },
        )
        else -> Unit
    }
}

private fun JsSelectFilter.selectedKey(): String =
    keys.getOrNull(state) ?: values.getOrNull(state) ?: ""

private fun JsSortFilter.keyAt(index: Int): String =
    keys.getOrNull(index) ?: values.getOrNull(index) ?: ""
