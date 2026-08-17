# Branch: `pip-finish-based-wip`

## Status: Work in progress. NOT stable. Do not merge as-is.

Builds on `duplicate-instance-fix-stable`. Fixes the specific PIP-second-entry crash
described below, but introduces a cascade of new, more subtle bugs. Kept here for
reference and to continue from, not for immediate use.

## The bug being chased

Sequence: enter PIP → tap the headphones "Background Play" button (backgrounds the
task while staying pinned, then calls `moveTaskToBack()`) → reopen from the
notification → try to enter PIP a second time → **app closes**.

Confirmed early on: this is *not* "PIP re-entry in general is broken." Repeated PIP
cycling done entirely in-app (via the back gesture or the on-screen PIP button, without
ever backgrounding through the notification) works fine, repeatedly, on the same
Activity instance. The crash is specifically tied to the
`moveTaskToBack()`-while-pinned + notification-reopen + re-enter-PIP combination.

## Everything tried, in order, and what was learned

Many fixes were attempted and verified to be *real, correct fixes for real problems* -
but none of them, individually, stopped the crash. Each is described here because the
reasoning and the ruled-out mechanisms are valuable even though the fixes didn't fully
resolve the issue:

1. **Auto-PIP-enter-on-Home suppression** (`AUTO_PIP_LOOP_FIX`) - the headphones
   button's `moveTaskToBack()` was being immediately undone by the OS's own auto-enter
   PIP heuristic re-triggering on the same "leaving" signal, causing a genuine infinite
   loop (confirmed via logcat: a fresh Android Task every ~4.5s, a fresh mpv instance
   constructed every cycle). Fixed by disabling `PictureInPictureParams.autoEnterEnabled`
   before this specific transition, and by checking a static,
   cross-instance-surviving flag (`SecureActivityDelegate.isBackgroundPlaybackActive`)
   rather than a per-instance flag, since the loop involved a fresh Activity instance
   every cycle. This fix was real and is kept.

2. **`onUserLeaveHint()` re-entering PIP on top of an intentional background
   transition** - `moveTaskToBack()` is itself one of the standard triggers for
   `onUserLeaveHint()`, which was unconditionally trying to re-enter PIP. Fixed with
   the same static-flag check. Real fix, kept.

3. **`PictureInPictureParams` re-registration undoing the disable** - a trailing,
   unconditional `setPictureInPictureParams()` call was recomputing params fresh
   (auto-enter still true) right after the disable, undoing it. Fixed by returning
   early from that specific action's branch. Real fix, kept.

4. **Guessed at an `IllegalStateException` from re-entering PIP while already
   transitioning** - added `!isInPictureInPictureMode` guards to all three
   `enterPictureInPictureMode()` call sites. Did not fix the crash (wrong exception).

5. **A genuine, confirmed Android framework race** - logcat directly showed:
   `java.lang.RuntimeException: Performing pause of activity that is not resumed`,
   thrown from inside `ActivityThread.performPauseActivity()` - **before** any of this
   app's own `onPause()` Kotlin code runs. This is a race between two concurrent
   system-level window transactions for the same Activity, not between two things the
   app's own code does. Guarded PIP-entry calls with a `lifecycle.currentState ==
   RESUMED` check. Did not fully resolve the crash, though the specific exception
   signature was later confirmed gone (see #10).

6. **Deferred `enterPictureInPictureMode()` via `Handler.post()`** - theorized the
   system's own Home-triggered launcher transition and the app's PIP-entry request
   were racing, since they start processing within the same millisecond in the logs.
   Did not help - and was later corrected: Android's documented pattern is to call
   `enterPictureInPictureMode()` **synchronously** from `onUserLeaveHint()`; deferring
   it works against the platform. Reverted.

7. **Deferred `moveTaskToBack()` instead** - `moveTaskToBack()` on a still-pinned task,
   called from inside a PIP action-button's own broadcast dispatch, is a genuinely
   unusual operation with little precedent in normal PIP apps (nothing like YouTube has
   an equivalent "force-exit an active PIP window into full background" button).
   Deferred this specific call via `Handler.post()` instead. Did not resolve the crash
   on its own, but see #9 - this deferral is very likely what eliminated the specific
   framework exception from #5/#10.

8. **`isPipEntryPending` tracking + `recreate()` on repeated PIP entry** - reasoned
   that a genuinely fresh Activity *record* (not just a fresh instance) might be the
   real requirement, since duplicate Activity instances (before they were eliminated in
   `duplicate-instance-fix-stable`) never exhibited this crash. Added a per-instance
   "have I attempted PIP entry before" flag; any attempt after the first triggers
   `recreate()` (destroy+rebuild in place, same Task) instead of calling
   `enterPictureInPictureMode()` directly, deferring the actual entry to the freshly
   recreated instance's own `onResume()`. **Confirmed to still crash** - and
   importantly, confirmed via a detectable audio clip that `recreate()` genuinely fired
   this time, meaning even a truly fresh Activity *record* within the same Task still
   hits this. This pointed at the problem living at the **Task level**, not the
   Activity-instance level. Reverted in this branch's final state (see below).

9. **Full unfiltered logcat capture requested and reviewed end-to-end** (not
   pre-filtered against a specific hypothesis) - found the actual, previously-missed
   exception:
   ```
   java.lang.RuntimeException: Error receiving broadcast Intent { act=pip_control ... }
     in PlayerActivity$onPictureInPictureModeChanged$5
   Caused by: java.lang.IllegalStateException: setPictureInPictureParams: Can't find
     activity for token=...
   ```
   Thrown from the app's own `pipReceiver` (the PIP action-button `BroadcastReceiver`)
   trying to call `setPictureInPictureParams()` on an Activity the system had already
   destroyed. This receiver was only ever unregistered as part of
   `onPictureInPictureModeChanged`'s own cleanup, never directly in `onDestroy()`.

10. **Direct user observation that reframed the whole investigation**: two genuinely
    separate `PlayerActivity` instances/tasks *never* exhibit this crash - only a
    single instance surviving across the moveTaskToBack-while-pinned transition does.
    This pointed at `moveTaskToBack()` itself as the toxic operation (an unusual,
    rarely-exercised operation as far as Android's own PIP implementation testing
    goes), not at "PIP re-entry" as a general category.

## The fix that actually stopped the crash

Based on finding #10, the headphones "Background Play" button was changed from calling
`moveTaskToBack()` (while still pinned) to calling **`finish()`** instead - the
standard, heavily-exercised way every PIP app closes its window. The existing
session-preservation architecture (a dedicated `isBackgroundPlayTransitionFinish` flag,
checked by `onDestroy()`/`onPause()`'s teardown-vs-preserve logic, kept deliberately
separate from `isIntentionalBackgroundTransition` since `onPictureInPictureModeChanged`
can clear that one first) keeps the underlying player/Service alive across this exactly
as `moveTaskToBack()` used to - but now the *Task itself* genuinely ends, so the next
reopen creates a truly fresh Task, matching the one condition confirmed to never
exhibit the crash.

Combined with finding #9's fix (guard `pipReceiver.onReceive()` with
`isFinishing || isDestroyed`, plus an explicit unregister safety net in `onDestroy()`),
this **did stop the crash** - confirmed via multiple clean, multi-cycle test logs with
no crash, no ANR, no "Can't find activity" error.

## The cascade this caused

Genuinely destroying and recreating the Activity between every background/reopen cycle
(rather than reusing the same instance, which the codebase had implicitly assumed
throughout) exposed several latent, previously-impossible bugs:

- **PIP button silently disappearing after one cycle** - `updateHasPip()` (which sets
  `stateData.isPipAvailable`) was only ever called inside the "genuine reinit" branch of
  `onNewIntent()`, never the "already playing, skip reload" fast path. A fresh
  instance's `stateData` starts at its default (`false`), and nothing re-established it.
  **Fixed** (`PIP_AVAILABILITY_FASTPATH_FIX`) by moving the call out of the reinit-only
  block - a pure device/preference capability check with no dependency on
  session-specific data, safe to run unconditionally.

- **Audio flickering on reconnect** - traced to `MPVPlayer`'s audio focus request
  happening unconditionally at construction time (inside `init{}`), before
  `PlayerMediaHolder.adopt()`'s orphan-dedup logic (which runs later, asynchronously,
  once the Service bind completes) has a chance to determine whether this player will
  actually be kept. Any scenario producing even a briefly-orphaned `MPVPlayer`
  construction was requesting live audio focus during that gap, causing Android to
  notify the actually-playing canonical player of a focus loss. **Fixed**
  (`AUDIO_FOCUS_ORPHAN_FIX`) by splitting the focus request out of `setupAudio()` into
  its own `requestAudioFocus()` function, called explicitly from
  `PlayerViewModel.bindToService()` only once confirmed to be a genuinely first-ever
  adoption (via a new `PlayerMediaHolder.hasAdoptedPlayer` check, read *before* calling
  `adopt()`).

- **Episode/playlist state getting confused; "no source available" errors; jumping
  back to the originally-opened episode** - not fully root-caused before this branch
  was shelved. The working theory: `PlayerBackgroundPlaybackService`'s stored
  `animeId`/`episodeId` (used to build the notification's reopen intent) are kept in
  sync via a reactive observer wired up from within `PlayerActivity`. If that observer
  is tied to the Activity's own lifecycle scope, it stops updating the moment the
  Activity is destroyed - meaning episode navigation that happens while genuinely
  backgrounded (skip next/previous from the notification or PIP controls, which route
  through the Service-owned `MediaSession` callback) may not correctly propagate,
  leaving the next reopen pointed at a stale episode. Unconfirmed and unfixed.

- **`"Unknown error"` toast, traced to a database-layer race** - logcat showed:
  ```
  E/GetAnime: java.lang.NullPointerException: ResultSet returned null for
    animes.sq:getAnimeById
  ```
  happening specifically on a third reopen cycle (not the first two), inside
  `viewModel.init()`'s `getAnime.await(animeId)` call - which appears to catch this
  internally and return `null` rather than propagating the exception, hence the
  generic "Unknown error" fallback (`Pair(defaultResult, Result.success(false))`,
  marked `// Unlikely but okay` in the original code) rather than a crash with a real
  stack trace. Not root-caused - unclear whether this is a rare pre-existing edge case
  now being hit more often due to repeated destroy/recreate cycles, or a race
  specifically introduced by this branch's changes.

- **Occasional ANRs** - reported but never actually captured in a logcat trace (the
  capture windows either missed the event or the circular buffer rolled over it before
  it could be reviewed). Unconfirmed mechanism.

- **App occasionally re-locking on reopen from notification** - reported, not
  investigated before this branch was shelved.

## Current state of this branch

The `recreate()`-based mechanism from finding #8 has been **reverted** in this
branch's final commit (it was superseded by the `finish()`-based fix and was adding
unnecessary Activity churn for no benefit once `finish()` proved sufficient). What
remains on this branch beyond `duplicate-instance-fix-stable`:

- `PIP_FINISH_NOT_MOVETASKTOBACK` - the headphones button uses `finish()`, not
  `moveTaskToBack()`.
- `PIP_RECEIVER_STALE_ACTIVITY_FIX` - `pipReceiver` guards against a destroyed
  Activity.
- `PIP_AVAILABILITY_FASTPATH_FIX` - PIP button visibility fix.
- `AUDIO_FOCUS_ORPHAN_FIX` - audio focus request timing fix.

## Where to pick this up

The original crash is fixed. The cascade needs root-causing, file by file, with the
same "the Activity can now genuinely not exist for a while" lens applied throughout the
codebase - the episode/playlist state bug and the database race are the two most
promising next threads, since both have partial evidence already gathered above. A
fresh, targeted logcat capture around the "no source available" error and the stale
episode reference specifically (rather than a broad multi-cycle capture) would likely
resolve both quickly, based on how effective that approach was for the crash itself.
