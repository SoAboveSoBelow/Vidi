package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

interface SecureActivityDelegate {
    fun registerSecureActivity(activity: AppCompatActivity)

    companion object {
        /**
         * Set to true if we need the first activity to authenticate.
         *
         * Always require unlock if app is killed.
         */
        var requireUnlock = true

        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        private var isPipActive = false
        private var isBackgroundServiceActive = false

        /**
         * True while mpv keeps running via PIP or the background-audio (notification)
         * service with no activity visible. The app-lock timer must not arm while this
         * is true, since the user is still actively using the player.
         *
         * AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
         * internal (not private) so setAppLock() below can also consult it directly -
         * see that call site for why.
         * <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
         */
        internal val isBackgroundPlaybackActive: Boolean
            get() = isPipActive || isBackgroundServiceActive

        /**
         * True if the most recent [onApplicationStopped] call was skipped because of
         * [isBackgroundPlaybackActive]. By the time [onApplicationStart] runs on return,
         * the activity has usually already cleared the flags above (e.g. onStart() stopping
         * the background service) - so onApplicationStart() can't just recheck them. This
         * remembers that the stop didn't count, so the next start doesn't arm the lock either.
         */
        private var wasBackgroundPlaybackExempt = false

        fun setPipActive(active: Boolean) {
            isPipActive = active
            if (!isBackgroundPlaybackActive) deferredApplicationStoppedCheck()
        }

        fun setBackgroundServiceActive(active: Boolean) {
            isBackgroundServiceActive = active
            if (!isBackgroundPlaybackActive) deferredApplicationStoppedCheck()
        }

        /**
         * Clears the PIP flag without triggering the catch-up [onApplicationStopped] check.
         * Use this specifically when PIP is exiting straight into background (notification)
         * playback: [eu.kanade.tachiyomi.ui.player.PlayerBackgroundPlaybackService] starting
         * is asynchronous, so [isBackgroundServiceActive] won't be true yet at this point even
         * though playback is genuinely continuing - a normal [setPipActive] call here would
         * see both flags false and wrongly consume the exemption before the service catches up.
         */
        fun clearPipForBackgroundHandoff() {
            isPipActive = false
        }

        /**
         * Posts the catch-up check instead of running it inline. When the last exemption
         * clears as part of resuming to the foreground (e.g. PlayerActivity.onStart() calling
         * stopBackgroundPlayback()), this runs mid-callback, before ProcessLifecycleOwner's own
         * state has updated to STARTED - so the isAtLeast(STARTED) guard below can't yet see
         * that we're resuming, and would wrongly arm the lock on every notification-to-player
         * switch. Posting defers evaluation until after the current lifecycle dispatch (and
         * ProcessLifecycleOwner's own state update) has settled.
         */
        private fun deferredApplicationStoppedCheck() {
            Handler(Looper.getMainLooper()).post { onApplicationStopped() }
        }
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

        fun onApplicationStopped() {
            val preferences = Injekt.get<SecurityPreferences>()
            if (!preferences.useAuthenticator.get()) return

            // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
            // Don't arm the lock timer while PIP or background (notification) playback
            // is keeping the player alive with the screen off.
            if (isBackgroundPlaybackActive) {
                wasBackgroundPlaybackExempt = true
                return
            }
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            wasBackgroundPlaybackExempt = false
            // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

            if (!AuthenticatorUtil.isAuthenticating) {
                // Return if app is closed in locked state
                if (requireUnlock) return
                // Save app close time if lock is delayed
                if (preferences.lockAppAfter.get() > 0) {
                    preferences.lastAppClosed.set(System.currentTimeMillis())
                }
            }
        }

        /**
         * Checks if unlock is needed when app comes foreground.
         */
        fun onApplicationStart() {
            val preferences = Injekt.get<SecurityPreferences>()
            if (!preferences.useAuthenticator.get()) return

            // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
            // The stop that preceded this start didn't count (PIP/background playback
            // was active) - don't recompute requireUnlock, including the "Always" case
            // below, which would otherwise ignore the exemption entirely.
            if (wasBackgroundPlaybackExempt) {
                wasBackgroundPlaybackExempt = false
                preferences.lastAppClosed.delete()
                return
            }
            // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

            val lastClosedPref = preferences.lastAppClosed

            // `requireUnlock` can be true on process start or if app was closed in locked state
            if (!AuthenticatorUtil.isAuthenticating && !requireUnlock) {
                requireUnlock = when (val lockDelay = preferences.lockAppAfter.get()) {
                    -1 -> false // Never
                    0 -> true // Always
                    else -> lastClosedPref.get() + lockDelay * 60_000 <= System.currentTimeMillis()
                }
            }

            lastClosedPref.delete()
        }

        fun unlock() {
            requireUnlock = false
        }
    }
}

class SecureActivityDelegateImpl : SecureActivityDelegate, DefaultLifecycleObserver {

    private lateinit var activity: AppCompatActivity

    private val preferences: BasePreferences by injectLazy()
    private val securityPreferences: SecurityPreferences by injectLazy()

    override fun registerSecureActivity(activity: AppCompatActivity) {
        this.activity = activity
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        setSecureScreen()
    }

    override fun onResume(owner: LifecycleOwner) {
        setAppLock()
    }

    private fun setSecureScreen() {
        val secureScreenFlow = securityPreferences.secureScreen.changes()
        val incognitoModeFlow = preferences.incognitoMode.changes()
        combine(secureScreenFlow, incognitoModeFlow) { secureScreen, incognitoMode ->
            secureScreen == SecurityPreferences.SecureScreenMode.ALWAYS ||
                (secureScreen == SecurityPreferences.SecureScreenMode.INCOGNITO && incognitoMode)
        }
            .onEach(activity.window::setSecureScreen)
            .launchIn(activity.lifecycleScope)
    }

    private fun setAppLock() {
        if (!securityPreferences.useAuthenticator.get()) return
        if (activity.isAuthenticationSupported()) {
            if (!SecureActivityDelegate.requireUnlock) return
            // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
            // Don't prompt for unlock while PIP or background (notification) playback
            // is actively keeping the player alive. This is the same guarantee that
            // already stops the lock timer from arming in that state (see
            // isBackgroundPlaybackActive above) - extended here to also cover the
            // prompt itself, not just whether it gets armed. Needed specifically
            // because a synthetic back-stack Activity (MainActivity, inserted ahead of
            // PlayerActivity so PlayerActivity is never its task's bare root when
            // reopened from the background-playback notification - see
            // PlayerBackgroundPlaybackService.buildReopenPendingIntent()) briefly
            // resumes as part of constructing that stack, running this same check
            // before PlayerActivity's own onStart()/exemption bookkeeping has had a
            // chance to run. Only ever short-circuits while playback is genuinely
            // still active in the background - idle-timeout locking with nothing
            // playing is untouched, since isBackgroundPlaybackActive is false there
            // and requireUnlock is evaluated normally above.
            if (SecureActivityDelegate.isBackgroundPlaybackActive) return
            // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
            activity.startActivity(Intent(activity, UnlockActivity::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        } else {
            securityPreferences.useAuthenticator.set(false)
        }
    }
}
