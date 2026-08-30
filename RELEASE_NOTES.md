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
