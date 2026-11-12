package animiru.domain.player.service

import animiru.domain.player.model.BottomPlayerButton
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlAnchor
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlInput
import animiru.domain.player.model.ControlLayout
import animiru.domain.player.model.ControlLayoutRecord
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.ControlSurface
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.model.SingleActionGesture
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSlots
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.domain.custombutton.repository.CustomButtonRepository

// AM (CONTROL_BINDINGS) -->

/**
 * Per-surface control bindings.
 *
 * One record per [ControlSurface] rather than one preference per binding: the old
 * scheme needed a new key, a new field and a new settings row for every slot added,
 * which doesn't survive user-orderable regions.
 *
 * Button options are shared by action identity. A one-time migration seeds them from
 * the current layout so existing installs retain their customized secondary actions.
 */
@Inject
@SingleIn(AppScope::class)
class ControlBindingPreferences(
    private val preferenceStore: PreferenceStore,
    private val customButtonRepository: CustomButtonRepository,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val rawLayouts = mutableMapOf<ControlSurface, Preference<ControlLayout>>()
    private val layouts = mutableMapOf<ControlSurface, Preference<ControlLayout>>()
    private val optionsMigrated by lazy {
        preferenceStore.getBoolean("pref_control_button_options_migrated", false)
    }
    private val storedButtonOptions by lazy {
        preferenceStore.getObjectFromString(
            key = "pref_control_button_options",
            defaultValue = emptyMap<ControlAction, ControlBinding>(),
            serializer = { options ->
                json.encodeToString(
                    options.mapKeys { (action, _) -> action.key }.mapValues { (_, binding) ->
                        ControlLayoutRecord.BindingRecord(
                            tap = binding.tap.key,
                            doubleTap = binding.doubleTap?.key,
                            longPress = binding.longPress?.key,
                        )
                    },
                )
            },
            deserializer = { stored ->
                runCatching {
                    json.decodeFromString<Map<String, ControlLayoutRecord.BindingRecord>>(stored)
                        .mapNotNull { (key, record) ->
                            ControlAction.fromKey(key)?.let { it to record.toBinding() }
                        }.toMap()
                }.getOrDefault(emptyMap())
            },
        )
    }

    @Synchronized
    private fun rawLayout(surface: ControlSurface): Preference<ControlLayout> = rawLayouts.getOrPut(surface) {
        preferenceStore.getObjectFromString(
            key = "pref_control_layout_${surface.key}",
            defaultValue = legacyLayout(surface),
            serializer = { json.encodeToString(ControlLayoutRecord.from(it)) },
            deserializer = { stored ->
                runCatching { json.decodeFromString<ControlLayoutRecord>(stored).toLayout().withoutFixedRegionControls() }
                    .getOrElse { legacyLayout(surface) }
            },
        )
    }

    @Synchronized
    fun layout(surface: ControlSurface): Preference<ControlLayout> = layouts.getOrPut(surface) {
        LayoutPreference(rawLayout(surface))
    }

    /** Shared tap, double-tap and hold behavior, keyed by the button placed in a slot. */
    fun buttonOptions(): Preference<Map<ControlAction, ControlBinding>> = ButtonOptionsPreference()

    @Synchronized
    private fun ensureButtonOptionsMigrated() {
        if (optionsMigrated.get()) return

        val options = defaultButtonOptions().toMutableMap()
        val seen = mutableSetOf<ControlAction>()
        rawLayout(ControlSurface.Player).get().regions.values.flatten().forEach { entry ->
            val button = entry.button ?: entry.tap
            if (button != ControlAction.None && seen.add(button)) {
                options[button] = ControlBinding(
                    tap = button,
                    doubleTap = entry.doubleTap,
                    longPress = entry.longPress,
                )
            }
        }
        storedButtonOptions.set(options)
        optionsMigrated.set(true)
    }

    private fun applyButtonOptions(
        layout: ControlLayout,
        options: Map<ControlAction, ControlBinding>,
        customButtons: List<CustomButton> = emptyList(),
        resolveNativeButtons: Boolean = false,
    ): ControlLayout {
        val customBindings = customButtons.associate { button ->
            val identity = ControlAction.CustomButton(button.id, icon = button.icon)
            identity to ControlBinding(
                tap = identity,
                doubleTap = ControlAction.CustomButton(button.id, ControlInput.DoubleTap, button.icon)
                    .takeIf { button.doubleTapContent.isNotBlank() },
                longPress = ControlAction.CustomButton(button.id, ControlInput.LongPress, button.icon)
                    .takeIf { button.longPressContent.isNotBlank() },
                button = identity,
            )
        }
        return layout.copy(
            regions = layout.regions.mapValues { (_, entries) ->
                entries.map { entry ->
                    val originalButton = entry.button ?: entry.tap
                    val nativeButtonId = ControlAction.nativeButtonId(originalButton)
                        .takeIf { resolveNativeButtons }
                    val originalCustomId = (originalButton as? ControlAction.CustomButton)?.id
                    val customId = nativeButtonId ?: originalCustomId
                    val customButton = customButtons.firstOrNull { it.id == customId }
                    val button = if (customId != null) {
                        ControlAction.CustomButton(customId, icon = customButton?.icon)
                    } else {
                        originalButton
                    }
                    val option = if (customId != null) customBindings[button] else options[button]
                    when {
                        option != null -> entry.copy(
                            tap = option.tap,
                            doubleTap = option.doubleTap,
                            longPress = option.longPress,
                            button = button,
                        )
                        // A deleted seeded button must not silently fall back to its old
                        // native action. Keep its stable identity so restoring/recreating
                        // the row can resolve the slot again.
                        nativeButtonId != null -> entry.copy(
                            tap = ControlAction.None,
                            doubleTap = null,
                            longPress = null,
                            button = button,
                        )
                        else -> entry
                    }
                }
            },
        )
    }

    private inner class LayoutPreference(
        private val source: Preference<ControlLayout>,
    ) : Preference<ControlLayout> {
        override fun key(): String = source.key()

        override fun get(): ControlLayout {
            ensureButtonOptionsMigrated()
            return applyButtonOptions(source.get(), storedButtonOptions.get())
        }

        override fun set(value: ControlLayout) = source.set(value)

        override fun isSet(): Boolean = source.isSet()

        override fun delete() = source.delete()

        override fun defaultValue(): ControlLayout = applyButtonOptions(
            source.defaultValue(),
            defaultButtonOptions(),
        )

        override fun changes(): Flow<ControlLayout> = combine(
            source.changes(),
            storedButtonOptions.changes(),
            customButtonRepository.subscribeAll(),
        ) { layout, options, customButtons ->
            applyButtonOptions(layout, options, customButtons, resolveNativeButtons = true)
        }

        override fun stateIn(scope: CoroutineScope): StateFlow<ControlLayout> =
            changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
    }

    private inner class ButtonOptionsPreference : Preference<Map<ControlAction, ControlBinding>> {
        override fun key(): String = storedButtonOptions.key()

        override fun get(): Map<ControlAction, ControlBinding> {
            ensureButtonOptionsMigrated()
            return defaultButtonOptions() + storedButtonOptions.get()
        }

        override fun set(value: Map<ControlAction, ControlBinding>) {
            storedButtonOptions.set(value)
            optionsMigrated.set(true)
        }

        override fun isSet(): Boolean = storedButtonOptions.isSet()

        override fun delete() {
            storedButtonOptions.delete()
            optionsMigrated.set(true)
        }

        override fun defaultValue(): Map<ControlAction, ControlBinding> = defaultButtonOptions()

        override fun changes(): Flow<Map<ControlAction, ControlBinding>> = storedButtonOptions.changes()
            .map { defaultButtonOptions() + it }

        override fun stateIn(scope: CoroutineScope): StateFlow<Map<ControlAction, ControlBinding>> =
            changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
    }

    /**
     * A view of one binding within a surface's layout, so a settings row can read and
     * write a single action without knowing the record it lives in.
     */
    fun action(
        surface: ControlSurface,
        anchor: ControlAnchor,
        input: ControlInput,
    ): Preference<ControlAction> = ActionPreference(layout(surface), anchor, input)

    /** A view of one region's ordered entries, for the editor and the settings dialog. */
    fun entries(
        surface: ControlSurface,
        region: ControlRegion,
    ): Preference<List<ControlBinding>> = EntriesPreference(layout(surface), region)

    private class EntriesPreference(
        private val source: Preference<ControlLayout>,
        private val region: ControlRegion,
    ) : Preference<List<ControlBinding>> {

        override fun key(): String = "${source.key()}.${region.key}"

        override fun get(): List<ControlBinding> = source.get().entries(region)

        override fun set(value: List<ControlBinding>) {
            source.set(source.get().withEntries(region, value.withoutFixedControls(region)))
        }

        override fun isSet(): Boolean = source.isSet()

        override fun delete() {
            source.set(source.get().withEntries(region, emptyList()))
        }

        override fun defaultValue(): List<ControlBinding> = source.defaultValue().entries(region)

        override fun changes(): Flow<List<ControlBinding>> = source.changes().map { it.entries(region) }

        override fun stateIn(scope: CoroutineScope): StateFlow<List<ControlBinding>> =
            changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
    }

    private class ActionPreference(
        private val source: Preference<ControlLayout>,
        private val anchor: ControlAnchor,
        private val input: ControlInput,
    ) : Preference<ControlAction> {

        override fun key(): String = "${source.key()}.${anchor.key}.${input.key}"

        override fun get(): ControlAction = source.get().action(anchor, input)

        override fun set(value: ControlAction) {
            source.set(source.get().withAction(anchor, input, value))
        }

        override fun isSet(): Boolean = source.isSet()

        override fun delete() {
            source.set(source.get().withAction(anchor, input, null))
        }

        override fun defaultValue(): ControlAction = source.defaultValue().action(anchor, input)

        override fun changes(): Flow<ControlAction> = source.changes().map { it.action(anchor, input) }

        override fun stateIn(scope: CoroutineScope): StateFlow<ControlAction> =
            changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
    }

    /**
     * The layout implied by the pre-[ControlBindingPreferences] keys.
     *
     * Direction is resolved here rather than at dispatch: the old `Seek` and `Switch`
     * meant backward on the left and forward on the right, which is knowledge the slot
     * used to carry and the action now does.
     */
    private fun legacyLayout(surface: ControlSurface): ControlLayout {
        if (surface == ControlSurface.DummyPip) return ControlLayout()

        fun legacy(key: String, default: SingleActionGesture) =
            preferenceStore.getEnum(key, default).get()

        val left = legacy("pref_left_double_tap", SingleActionGesture.Seek)
        val center = legacy("pref_center_double_tap", SingleActionGesture.PlayPause)
        val right = legacy("pref_right_double_tap", SingleActionGesture.Seek)
        val longPress = legacy("pref_long_press", SingleActionGesture.Screenshot)

        var layout = ControlLayout()
            .withAction(
                ControlAnchor.ScreenLeft,
                ControlInput.DoubleTap,
                left.toAction(CustomKeyCodes.DoubleTapLeft, forward = false),
            )
            .withAction(
                ControlAnchor.ScreenCenter,
                ControlInput.DoubleTap,
                center.toAction(CustomKeyCodes.DoubleTapCenter, forward = null),
            )
            .withAction(
                ControlAnchor.ScreenRight,
                ControlInput.DoubleTap,
                right.toAction(CustomKeyCodes.DoubleTapRight, forward = true),
            )
            .withAction(
                ControlAnchor.ScreenCenter,
                ControlInput.LongPress,
                // The old long press ran the primary custom button rather than sending a
                // keypress, so Custom maps to the button and not to a CustomKey.
                when (longPress) {
                    SingleActionGesture.Custom -> ControlAction.PrimaryCustomButton
                    else -> longPress.toAction(null, forward = null)
                },
            )

        if (surface == ControlSurface.Player) {
            // An empty legacy position becomes an entry bound to None rather than being
            // compacted away: the old row spaced its halves by index, so dropping the gap
            // would silently move everyone's buttons.
            val bottom = preferenceStore
                .getEnumSlots(
                    "pref_bottom_player_buttons",
                    listOf(
                        BottomPlayerButton.Lock,
                        BottomPlayerButton.Rotation,
                        BottomPlayerButton.Speed,
                        BottomPlayerButton.Chapters,
                        BottomPlayerButton.PictureInPicture,
                        BottomPlayerButton.AspectRatio,
                    ),
                )
                .get()
                .map { ControlBinding(tap = it.toAction()) }

            layout = layout.withEntries(ControlRegion.Bottom, bottom)

            val previousAction = legacy("pref_media_previous", SingleActionGesture.Switch)
                .toAction(CustomKeyCodes.MediaPrevious, forward = false)
            val playPauseAction = legacy("pref_media_playpause", SingleActionGesture.PlayPause)
                .toAction(CustomKeyCodes.MediaPlay, forward = null)
            val nextAction = legacy("pref_media_next", SingleActionGesture.Switch)
                .toAction(CustomKeyCodes.MediaNext, forward = true)

            // Play/pause is a fixed centre control, not a stored reorderable position.
            layout = layout.withEntries(
                ControlRegion.Middle,
                listOf(
                    ControlBinding(
                        tap = previousAction,
                        doubleTap = ControlAction.PreviousSource,
                    ),
                    ControlBinding(
                        tap = nextAction,
                        doubleTap = ControlAction.NextSource,
                    ),
                ),
            )

            // The top bar's cluster, in the order it was hard-coded in. The secondary
            // gestures that used to be built into each button are explicit bindings
            // now, so a slot has one answer for what it does rather than two.
            layout = layout.withEntries(
                ControlRegion.TopBar,
                listOf(
                    ControlBinding(tap = ControlAction.ToggleAutoPlay),
                    ControlBinding(
                        tap = ControlAction.SubtitleTracks,
                        longPress = ControlAction.SubtitleSettings,
                    ),
                    ControlBinding(
                        tap = ControlAction.AudioTracks,
                        longPress = ControlAction.AudioDelay,
                    ),
                    ControlBinding(
                        tap = ControlAction.VideoQuality,
                        longPress = ControlAction.VideoQuality,
                    ),
                ),
            )

            // The session's own three buttons. Same starting actions as the row's, but
            // a separate record: a lock screen is not the player, and the two stop
            // being the same thing the moment either is edited.
            layout = layout
                .withAction(ControlAnchor.MediaPrevious, ControlInput.Tap, previousAction)
                .withAction(ControlAnchor.MediaPlayPause, ControlInput.Tap, playPauseAction)
                .withAction(ControlAnchor.MediaNext, ControlInput.Tap, nextAction)
        }

        return layout
    }

    private fun BottomPlayerButton?.toAction(): ControlAction = when (this) {
        null -> ControlAction.None
        BottomPlayerButton.Lock -> ControlAction.Lock
        BottomPlayerButton.Rotation -> ControlAction.CycleRotation
        BottomPlayerButton.Speed -> ControlAction.PlaybackSpeed
        BottomPlayerButton.Chapters -> ControlAction.Chapters
        BottomPlayerButton.PictureInPicture -> ControlAction.PictureInPicture
        BottomPlayerButton.AspectRatio -> ControlAction.AspectRatio
        BottomPlayerButton.Screenshot -> ControlAction.Screenshot
        BottomPlayerButton.Cast -> ControlAction.Cast
    }

    /**
     * [forward] is the direction the slot used to imply: null for a centre slot, where
     * the old handlers did nothing at all for Seek and Switch.
     */
    private fun SingleActionGesture.toAction(
        customKey: CustomKeyCodes?,
        forward: Boolean?,
    ): ControlAction = when (this) {
        SingleActionGesture.None -> ControlAction.None
        SingleActionGesture.PlayPause -> ControlAction.PlayPause
        SingleActionGesture.Screenshot -> ControlAction.Screenshot
        SingleActionGesture.Seek -> when (forward) {
            true -> ControlAction.SeekForward
            false -> ControlAction.SeekBackward
            null -> ControlAction.None
        }
        SingleActionGesture.Switch -> when (forward) {
            true -> ControlAction.NextEpisode
            false -> ControlAction.PreviousEpisode
            null -> ControlAction.None
        }
        SingleActionGesture.Custom -> customKey
            ?.let { ControlAction.CustomKey(it.keyCode) }
            ?: ControlAction.None
    }
}

private fun defaultButtonOptions(): Map<ControlAction, ControlBinding> = ControlAction.selectable
    .filterNot { it == ControlAction.None }
    .associateWith { action ->
        ControlBinding(
            tap = action,
            doubleTap = when (action) {
                ControlAction.PreviousEpisode -> ControlAction.PreviousSource
                ControlAction.NextEpisode -> ControlAction.NextSource
                else -> null
            },
            longPress = when (action) {
                ControlAction.SubtitleTracks -> ControlAction.SubtitleSettings
                ControlAction.AudioTracks -> ControlAction.AudioDelay
                ControlAction.VideoQuality -> ControlAction.VideoQuality
                else -> null
            },
        )
    }

private fun ControlLayout.withoutFixedRegionControls(): ControlLayout =
    ControlRegion.entries.fold(this) { result, region ->
        result.withEntries(region, result.entries(region).withoutFixedControls(region))
    }

private fun List<ControlBinding>.withoutFixedControls(region: ControlRegion) = filterNot { entry ->
    when (region) {
        ControlRegion.TopBar -> (entry.button ?: entry.tap) == ControlAction.Overflow
        ControlRegion.Middle -> (entry.button ?: entry.tap) == ControlAction.PlayPause
        ControlRegion.Bottom -> false
    }
}
// <-- AM (CONTROL_BINDINGS)
