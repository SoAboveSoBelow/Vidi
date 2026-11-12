package animiru.domain.player.model

import dev.icerock.moko.resources.StringResource
import kotlinx.serialization.Serializable
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR

// AM (CONTROL_BINDINGS) -->

/**
 * A UI that hosts user-bindable controls.
 *
 * Each surface owns its own layout record, so the player and the scroller can diverge
 * even though they currently share most of their actions. [key] is persisted - renaming
 * an entry orphans that surface's stored layout, so don't.
 */
enum class ControlSurface(val key: String) {
    Player("player"),
    Scroller("scroller"),
    DummyPip("dummy_pip"),
}

/**
 * A bindable position whose place is fixed by the surface's structure rather than by
 * user ordering.
 *
 * The user-orderable region slots (top bar, middle, bottom) arrive with the region work;
 * until then the three media buttons are anchors because that is what they are today -
 * fixed positions either side of a fixed centre.
 */
enum class ControlAnchor(val key: String) {
    ScreenLeft("screen_left"),
    ScreenCenter("screen_center"),
    ScreenRight("screen_right"),
    MediaPrevious("media_previous"),
    MediaPlayPause("media_play_pause"),
    MediaNext("media_next"),
}

/**
 * A band of user-ordered slots within a surface.
 *
 * [hardCap] bounds the stored list only. How many slots actually render is measured at
 * layout time from the width available, so a narrower window shows fewer of them without
 * the stored list ever being rewritten - see slotCapacity.
 */
enum class ControlRegion(val key: String, val hardCap: Int) {
    TopBar("top_bar", 8),
    Middle("middle", 9),
    Bottom("bottom", 8),
}

/** Stable identity of a position, whether occupied, blank, or not stored yet. */
data class ControlSlotId(val region: ControlRegion, val index: Int)

/** How a bindable position was touched. */
enum class ControlInput(val key: String) {
    Tap("tap"),
    DoubleTap("double_tap"),
    LongPress("long_press"),
}

/**
 * Something a control can be bound to do.
 *
 * Replaces [SingleActionGesture], whose directionless `Seek` and `Switch` only worked
 * because the handler knew which side of the screen it was called from. An action that
 * carries its own direction can be bound anywhere, which is what lets one picker serve
 * every slot.
 */
sealed interface ControlAction {

    /** Persisted identifier. Stable across releases - see [fromKey]. */
    val key: String

    val stringRes: StringResource

    data object None : ControlAction {
        override val key = "none"
        override val stringRes = AYMR.strings.single_action_none
    }

    data object PlayPause : ControlAction {
        override val key = "play_pause"
        override val stringRes = AYMR.strings.single_action_playpause
    }

    data object SeekBackward : ControlAction {
        override val key = "seek_backward"
        override val stringRes = AYMR.strings.control_action_seek_backward
    }

    data object SeekForward : ControlAction {
        override val key = "seek_forward"
        override val stringRes = AYMR.strings.control_action_seek_forward
    }

    data object PreviousEpisode : ControlAction {
        override val key = "previous_episode"
        override val stringRes = AYMR.strings.control_action_previous_episode
    }

    data object NextEpisode : ControlAction {
        override val key = "next_episode"
        override val stringRes = AYMR.strings.control_action_next_episode
    }

    /** Steps a whole queued source rather than an episode. */
    data object PreviousSource : ControlAction {
        override val key = "previous_source"
        override val stringRes = AYMR.strings.control_action_previous_source
    }

    data object NextSource : ControlAction {
        override val key = "next_source"
        override val stringRes = AYMR.strings.control_action_next_source
    }

    data object Screenshot : ControlAction {
        override val key = "screenshot"
        override val stringRes = AYMR.strings.single_action_screenshot
    }

    data object Lock : ControlAction {
        override val key = "lock"
        override val stringRes = AYMR.strings.pref_bottom_button_lock
    }

    data object CycleRotation : ControlAction {
        override val key = "cycle_rotation"
        override val stringRes = AYMR.strings.pref_bottom_button_rotation
    }

    data object PlaybackSpeed : ControlAction {
        override val key = "playback_speed"
        override val stringRes = AYMR.strings.pref_bottom_button_speed
    }

    data object Chapters : ControlAction {
        override val key = "chapters"
        override val stringRes = AYMR.strings.pref_bottom_button_chapters
    }

    data object PictureInPicture : ControlAction {
        override val key = "picture_in_picture"
        override val stringRes = AYMR.strings.pref_bottom_button_pip
    }

    data object AspectRatio : ControlAction {
        override val key = "aspect_ratio"
        override val stringRes = AYMR.strings.pref_bottom_button_aspect_ratio
    }

    data object Cast : ControlAction {
        override val key = "cast"
        override val stringRes = AMMR.strings.pref_cast
    }

    data object ToggleAutoPlay : ControlAction {
        override val key = "toggle_auto_play"
        override val stringRes = AYMR.strings.control_action_auto_play
    }

    data object SubtitleTracks : ControlAction {
        override val key = "subtitle_tracks"
        override val stringRes = AYMR.strings.pref_player_subtitle
    }

    data object SubtitleSettings : ControlAction {
        override val key = "subtitle_settings"
        override val stringRes = AYMR.strings.player_sheets_subtitles_settings_title
    }

    data object AudioTracks : ControlAction {
        override val key = "audio_tracks"
        override val stringRes = AYMR.strings.pref_player_audio
    }

    data object AudioDelay : ControlAction {
        override val key = "audio_delay"
        override val stringRes = AYMR.strings.player_sheets_audio_delay_title
    }

    data object VideoQuality : ControlAction {
        override val key = "video_quality"
        override val stringRes = AYMR.strings.player_sheets_qualities_title
    }

    data object VideoFilters : ControlAction {
        override val key = "video_filters"
        override val stringRes = AYMR.strings.player_sheets_filters_title
    }

    data object Overflow : ControlAction {
        override val key = "overflow"
        override val stringRes = AYMR.strings.player_sheets_more_title
    }

    /** Runs whichever custom button the user marked primary. */
    data object PrimaryCustomButton : ControlAction {
        override val key = "primary_custom_button"
        override val stringRes = AYMR.strings.single_action_custom
    }

    /** Runs one input of a user-created Lua button. */
    data class CustomButton(
        val id: Long,
        val input: ControlInput = ControlInput.Tap,
        /** Presentation only; deliberately excluded from the persisted action key. */
        val icon: String? = null,
    ) : ControlAction {
        override val key = "$CUSTOM_BUTTON_PREFIX$id:${input.key}"
        override val stringRes = AYMR.strings.single_action_custom
    }

    /**
     * Sends a raw keypress to mpv, for users who bound the slot in their own Lua.
     *
     * The code used to be implied by the slot ([CustomKeyCodes]); carrying it on the
     * action keeps those bindings working once slots stop being fixed positions.
     */
    data class CustomKey(val code: String) : ControlAction {
        override val key = "$CUSTOM_KEY_PREFIX$code"
        override val stringRes = AYMR.strings.single_action_custom
    }

    companion object {
        private const val CUSTOM_KEY_PREFIX = "custom_key:"
        private const val CUSTOM_BUTTON_PREFIX = "custom_button:"

        /** Actions offered by every picker, in display order. Direction filtering is the caller's. */
        val selectable: List<ControlAction> = listOf(
            None,
            PlayPause,
            SeekBackward,
            SeekForward,
            PreviousEpisode,
            NextEpisode,
            PreviousSource,
            NextSource,
            Screenshot,
            Lock,
            CycleRotation,
            PlaybackSpeed,
            Chapters,
            PictureInPicture,
            AspectRatio,
            Cast,
            ToggleAutoPlay,
            SubtitleTracks,
            SubtitleSettings,
            AudioTracks,
            AudioDelay,
            VideoQuality,
            VideoFilters,
            Overflow,
            PrimaryCustomButton,
        )

        /** Stable seeded Lua row for a native default button; user buttons use positive IDs. */
        fun nativeButtonId(action: ControlAction): Long? = selectable.indexOf(action)
            .takeIf { it > 0 }
            ?.let { -(it + 1).toLong() }

        /** Original presentation for a seeded default, or null for a user-created button. */
        fun nativeActionForButtonId(id: Long): ControlAction? = if (id <= -2L && id >= -25L) {
            selectable.getOrNull((-id).toInt() - 1)
        } else {
            null
        }

        /**
         * Returns null for an unrecognised key rather than falling back to [None], so a
         * layout written by a newer release keeps its other bindings instead of being
         * silently blanked. Callers decide what an unreadable slot means for them.
         */
        fun fromKey(key: String): ControlAction? = when {
            key.startsWith(CUSTOM_KEY_PREFIX) -> CustomKey(key.removePrefix(CUSTOM_KEY_PREFIX))
            key.startsWith(CUSTOM_BUTTON_PREFIX) -> {
                val parts = key.removePrefix(CUSTOM_BUTTON_PREFIX).split(':', limit = 2)
                val id = parts.getOrNull(0)?.toLongOrNull()
                val input = parts.getOrNull(1)?.let { stored ->
                    ControlInput.entries.firstOrNull { it.key == stored }
                }
                if (id != null && input != null) CustomButton(id, input) else null
            }
            else -> selectable.firstOrNull { it.key == key }
        }
    }
}

/**
 * What one position does, per input.
 *
 * [tap] also supplies the position's identity in the editor - a slot showing the PiP icon
 * is the slot whose tap enters PiP - so it is never null. The other two default to nothing
 * bound, which is what lets long press exist in the schema without shipping any defaults.
 */
data class ControlBinding(
    val tap: ControlAction = ControlAction.None,
    val doubleTap: ControlAction? = null,
    val longPress: ControlAction? = null,
    /** Stable button-option identity; [tap] is its editable single-tap action. */
    val button: ControlAction? = null,
) {
    fun forInput(input: ControlInput): ControlAction? = when (input) {
        ControlInput.Tap -> tap
        ControlInput.DoubleTap -> doubleTap
        ControlInput.LongPress -> longPress
    }

    fun withInput(input: ControlInput, action: ControlAction?): ControlBinding = when (input) {
        ControlInput.Tap -> copy(tap = action ?: ControlAction.None)
        ControlInput.DoubleTap -> copy(doubleTap = action)
        ControlInput.LongPress -> copy(longPress = action)
    }

    /**
     * Whether a tap here has to wait out the double-tap timeout before firing.
     *
     * Only true for slots that actually carry a double-tap binding: making every control
     * wait would make the whole surface feel slow for a feature most users never bind.
     */
    val tapIsDeferred: Boolean get() = doubleTap != null
}

/**
 * How many slots of a region actually fit.
 *
 * Measured rather than bucketed: a density or orientation bucket is wrong for foldables,
 * split screen, freeform and desktop windows, all of which resize without changing either.
 * The result only ever truncates what is rendered - the stored list keeps its full length,
 * so slots that don't fit in one window reappear in a wider one.
 */
fun slotCapacity(
    regionWidthPx: Int,
    anchorsWidthPx: Int,
    minSlotWidthPx: Int,
    /**
     * Gap the row leaves BETWEEN slots. Counted here rather than folded into
     * [minSlotWidthPx]: a row of n slots has n-1 gaps, not n, so adding the gap to
     * the slot width undercounts by one slot's worth of space every time - which is
     * what made a bar that fits five report four.
     */
    slotSpacingPx: Int = 0,
): Int {
    if (minSlotWidthPx <= 0) return 0
    val available = regionWidthPx - anchorsWidthPx
    if (available < minSlotWidthPx) return 0
    return ((available + slotSpacingPx) / (minSlotWidthPx + slotSpacingPx)).coerceAtLeast(0)
}

/**
 * How many empty positions still fit beside the controls a row is already drawing.
 *
 * [filledWidthPx] is the width those controls actually take, not a per-slot average
 * multiplied out: no row here is uniform. An autoplay switch is wider than an icon, a
 * speed button is as wide as its label, the play control is wider than either. Counting
 * a row in average slots therefore overcounts a bar holding a wide control - which is
 * how a full top bar was offered a position past its end - and undercounts one holding
 * only narrow ones, which is how a bottom bar with room for a sixth refused to offer it.
 *
 * The gap is charged once per added position rather than once per gap in the finished
 * row, because [filledWidthPx] already carries the gaps between the controls it covers.
 * With nothing filled there is one fewer gap than positions, and that case is allowed
 * for explicitly.
 */
fun freeSlots(
    availableWidthPx: Int,
    filledWidthPx: Int,
    slotWidthPx: Int,
    slotSpacingPx: Int = 0,
): Int {
    if (slotWidthPx <= 0) return 0
    val remaining = availableWidthPx - filledWidthPx + if (filledWidthPx > 0) 0 else slotSpacingPx
    if (remaining < slotWidthPx) return 0
    return (remaining / (slotWidthPx + slotSpacingPx)).coerceAtLeast(0)
}

/** One surface's bindings. */
data class ControlLayout(
    val anchors: Map<ControlAnchor, ControlBinding> = emptyMap(),
    val regions: Map<ControlRegion, List<ControlBinding>> = emptyMap(),
) {
    fun binding(anchor: ControlAnchor): ControlBinding = anchors[anchor] ?: ControlBinding()

    fun entries(region: ControlRegion): List<ControlBinding> = regions[region].orEmpty()

    /**
     * The entries that fit in [capacity], without touching the stored list. Callers render
     * this; nothing writes it back.
     */
    fun visibleEntries(region: ControlRegion, capacity: Int): List<ControlBinding> =
        entries(region).take(capacity.coerceAtLeast(0))

    fun withEntries(region: ControlRegion, entries: List<ControlBinding>): ControlLayout =
        copy(regions = regions + (region to entries))

    fun action(anchor: ControlAnchor, input: ControlInput): ControlAction =
        binding(anchor).forInput(input) ?: ControlAction.None

    fun withAction(anchor: ControlAnchor, input: ControlInput, action: ControlAction?): ControlLayout =
        copy(anchors = anchors + (anchor to binding(anchor).withInput(input, action)))
}

/**
 * Wire format for [ControlLayout].
 *
 * Actions are stored as their string keys, not as enum ordinals or names, so an action
 * this build doesn't know about survives a round trip through an older release instead of
 * being dropped on read. [version] exists for the same reason - a later schema change can
 * upgrade rather than discard.
 */
@Serializable
data class ControlLayoutRecord(
    val version: Int = CURRENT_VERSION,
    val anchors: Map<String, BindingRecord> = emptyMap(),
    val regions: Map<String, List<BindingRecord>> = emptyMap(),
) {
    @Serializable
    data class BindingRecord(
        val tap: String = ControlAction.None.key,
        val doubleTap: String? = null,
        val longPress: String? = null,
        val button: String? = null,
    ) {
        fun toBinding(): ControlBinding = ControlBinding(
            tap = ControlAction.fromKey(tap) ?: ControlAction.None,
            doubleTap = doubleTap?.let(ControlAction::fromKey),
            longPress = longPress?.let(ControlAction::fromKey),
            button = button?.let(ControlAction::fromKey),
        )
    }

    fun toLayout(): ControlLayout = ControlLayout(
        anchors = anchors.mapNotNull { (key, record) ->
            val anchor = ControlAnchor.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
            anchor to record.toBinding()
        }.toMap(),
        regions = regions.mapNotNull { (key, records) ->
            val region = ControlRegion.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
            region to records.map { it.toBinding() }
        }.toMap(),
    )

    companion object {
        const val CURRENT_VERSION = 2

        fun from(layout: ControlLayout): ControlLayoutRecord = ControlLayoutRecord(
            anchors = layout.anchors.entries.associate { (anchor, binding) ->
                anchor.key to binding.toRecord()
            },
            regions = layout.regions.entries.associate { (region, entries) ->
                region.key to entries.map { it.toRecord() }
            },
        )

        private fun ControlBinding.toRecord(): BindingRecord = BindingRecord(
            tap = tap.key,
            doubleTap = doubleTap?.key,
            longPress = longPress?.key,
            button = button?.key,
        )
    }
}
// <-- AM (CONTROL_BINDINGS)
