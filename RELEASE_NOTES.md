## v0.20.1

Player controls:
- Customizable control layouts: adjustable slots across the header, middle and bottom rows, with a bound action per slot and a double-tap action for any of them
- Custom buttons are now editable Lua player controls, bound through that same control-binding system rather than their own settings page

Playback fixes:
- Episode switches load in place on the live instance again. The prepared second-instance handoff is kept for quality switches only, which is what it was built for - a second instance meant a second video output and a texture handed between two TextureViews at the swap, which is where the wrong, half-size and late first frames came from
- Progress writers only write while the live player holds the current episode, so a position from the outgoing timeline is no longer recorded against the incoming episode
- One definition of the resume point, shared by the player and the UI; an end-of-episode stored position is no longer treated as one
- Surface attachment is published from one place, so an instance that adopts an existing texture reports it; a prepared instance no longer forces a window or renders at 3x3
- A prefetch resolve outlives the loop that started it, and joining one no longer propagates its cancellation into the episode load
- Fixed the player notification lifeline, episode prefetch, loader flicker and the scroller decode path
- Fixed a crash when a TextureView was reused across two mpv instances: setSurfaceTexture releases whatever the view holds, which could be a texture its own player still pointed at

Media cache:
- Retention no longer follows the temp-position table. Bytes accumulate against their own ceiling and are evicted oldest-first with the newest trimmed to its resume window; resuming an episode no longer makes its own bytes evictable, and the position-slot count no longer decides how many episodes may keep media
- A proxy registration outlives its session while the cache still holds the episode, so cached bytes stay readable without re-resolving the stream
- Resolved stream data is pruned on TTL or queue departure and nothing else; the slot cap is gone

Browse:
- Hide viewed: a per-source toggle in the browse overflow that trims entries already opened - started in the player or played as a card in the scroller feed, which is not the same as marked seen. Persisted per source, so a source left with it on opens with its first page already trimmed
- Browse merge adds only the merged parent, and library updates cover merge children
- Merge settings stays open when a source is removed

Other:
- Player controls hide video-dependent actions when unavailable and respect the top bar's reserved start; the chapter slot fills the row's gap
- The dummy pip publishes its expand on a cancelled exit morph
- JS projects are always included in backups

## v0.19.11

Playback lifecycle fixes:
- Fixed native mpv/GPU context leak on every backgrounding transition (PlayerMediaHolder.release() now calls the real MPVPlayer.release() instead of poking isExiting directly)
- Fixed resume-position bleed between different series in recentEpisodePositions and the live-holder-state resume check, both previously keyed by episode ID alone
- Fixed opening a different series mid-playback: ViewModel now builds a fresh MPVPlayer instead of reusing a mismatched live one; PlayerActivity.onNewIntent() does a real teardown-and-relaunch for a different-anime request

Notification fixes:
- Fixed notification swipe-dismiss not stopping background playback: ID_HTTP_SERVER and ID_BACKGROUND_PLAYBACK notification IDs collided (both -901); setOngoing(true) was also blocking Android's swipe-dismiss callback entirely, now removed

Browse/library fixes:
- Fixed cover/background image churn and crashes on missing covers in anime detail views
- Fixed loading spinner never clearing for videos without audio
- Isolated per-item source parser crashes so one bad catalog entry no longer takes out the whole page
- Fixed a blocking call in the legacy RxJava network bridge that every extension treats as async

- SqlDriver is now a hard singleton instead of WeakReference-held
## v0.19.10 (pip-finish-based-wip merge)

PIP and background playback lifecycle fixes:
- Fixed PIP "Background Play" button incorrectly calling finish(); reverted to moveTaskToBack() with a new "Reduce battery drain" toggle in Player Settings -> PIP
- Fixed audio blip on backgrounding via TextureView switch, now that Activity survival is ensured
- Fixed hasNavigatedBack permanent latch with a 500ms debounce reset
- Fixed a 411ms timing gap causing false app-lock triggers via proactive SecureActivityDelegate.setPipActive(true)
- Fixed mpv.close() on PIP dismissal
- Swipe-away now pauses playback before backgrounding

Media notification fixes:
- PlayerMediaHolder now owns a single always-active episode-change observer; PlayerActivity's independent observer removed
- MediaSession.setCallback() pinned to main-looper Handler

Known open issue:
- mpv's Android AudioTrack reinitializing during background transitions (ao_audiotrack driver / audio focus handling); confirmed via AudioPlayerStateMonitor logs showing a new sessionId at the second playback restart
