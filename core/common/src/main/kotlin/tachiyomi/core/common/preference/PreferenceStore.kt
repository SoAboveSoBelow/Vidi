package tachiyomi.core.common.preference

interface PreferenceStore {

    fun getString(key: String, defaultValue: String = ""): Preference<String>

    fun getLong(key: String, defaultValue: Long = 0): Preference<Long>

    fun getInt(key: String, defaultValue: Int = 0): Preference<Int>

    fun getFloat(key: String, defaultValue: Float = 0f): Preference<Float>

    fun getBoolean(key: String, defaultValue: Boolean = false): Preference<Boolean>

    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Preference<Set<String>>

    fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T>

    fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T>

    fun getAll(): Map<String, *>
}

fun PreferenceStore.getLongArray(
    key: String,
    defaultValue: List<Long>,
): Preference<List<Long>> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.joinToString(",") },
        deserializer = { it.split(",").mapNotNull { l -> l.toLongOrNull() } },
    )
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(
    key: String,
    defaultValue: T,
): Preference<T> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.name },
        deserializer = {
            try {
                enumValueOf(it)
            } catch (e: IllegalArgumentException) {
                defaultValue
            }
        },
    )
}

/**
 * Stores an ordered list of enum values as a comma-separated string of their names.
 * Unknown/removed names are silently dropped on read rather than falling back to the
 * default, so a single renamed or deleted entry doesn't wipe out the rest of the order.
 */
inline fun <reified T : Enum<T>> PreferenceStore.getEnumList(
    key: String,
    defaultValue: List<T>,
): Preference<List<T>> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { list -> list.joinToString(",") { it.name } },
        deserializer = { str ->
            if (str.isBlank()) {
                emptyList()
            } else {
                str.split(",").mapNotNull {
                    try {
                        enumValueOf<T>(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            }
        },
    )
}

/**
 * Stores a fixed-length ordered list of *positions*, each either an enum value or empty
 * (null), as a comma-separated string. Unlike [getEnumList], empty slots are preserved
 * on read/write rather than compacted away, so a gap at position 2 stays at position 2
 * instead of everything after it shifting up.
 */
inline fun <reified T : Enum<T>> PreferenceStore.getEnumSlots(
    key: String,
    defaultValue: List<T?>,
): Preference<List<T?>> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { list -> list.joinToString(",") { it?.name.orEmpty() } },
        deserializer = { str ->
            str.split(",").map { token ->
                if (token.isEmpty()) {
                    null
                } else {
                    try {
                        enumValueOf<T>(token)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            }
        },
    )
}
