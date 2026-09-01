package eu.kanade.tachiyomi.ui.player

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import logcat.LogPriority
import logcat.logcat

// AM (PIP_FAKE_NAV_RECENTS_SERVICE) -->
// GLOBAL_ACTION_RECENTS (genuinely opening the real system Recents/Overview
// screen) has no regular app-level API at all - it's only reachable through
// AccessibilityService.performGlobalAction(), confirmed via Android's own
// documentation. No Intent, no ActivityManager method, nothing else exposes
// it to a normal app. This service exists solely to make that one call
// available - it does not listen to or act on accessibility events, and
// registers for none beyond the bare minimum the framework requires.
//
// The user has to manually enable this once in system Accessibility
// settings (Settings.ACTION_ACCESSIBILITY_SETTINGS) - there's no way to
// prompt/auto-enable an accessibility service from inside an app, by
// design, so users aren't tricked into granting this scope. This is only
// appropriate for non-Play-Store distribution: an accessibility service
// used for a single global-action call like this would very likely be
// rejected in Play Store review, since Google restricts the API to genuine
// accessibility use cases.
//
// instance is a nullable static reference to the currently-connected
// service, set in onServiceConnected()/cleared in onDestroy() - the
// standard way to reach a running AccessibilityService from elsewhere in
// the app, since these aren't started/bound the normal way.
class RecentsAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RecentsAccessibilityService? = null
            private set

        val isEnabled: Boolean
            get() = instance != null

        fun openRecents(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        logcat(LogPriority.DEBUG) { "RecentsAccessibilityService connected" }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onInterrupt() {}

    // Intentionally does nothing - this service only exists for
    // performGlobalAction(), it doesn't need or want accessibility events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
}
// <-- AM (PIP_FAKE_NAV_RECENTS_SERVICE)
