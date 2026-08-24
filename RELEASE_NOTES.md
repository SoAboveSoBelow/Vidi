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
