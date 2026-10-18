package eu.kanade.tachiyomi.ui.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.aniyomi.AYMR

// AM (EXTERNAL_PLAYBACK) -->
/**
 * The only door other apps can open: takes an ACTION_VIEW or ACTION_SEND video,
 * turns it into a [PlaybackRequest.External], hands it to the player and
 * finishes.
 *
 * This exists so [PlayerActivity] can stay `exported="false"`. PlayerActivity
 * is `singleTask` with its own `taskAffinity` and a duplicate-instance guard,
 * and the comments around all three (see the manifest, PlayerActivity.newIntent
 * and onNewIntent) record how much work went into making those invariants hold
 * for the app's OWN callers - every one of which routes through
 * MainActivity.startPlayerActivity or the reopen PendingIntent, both building
 * intents this app controls completely. Exporting it would hand arbitrary apps
 * the ability to aim intents, with arbitrary flags, at the Activity those
 * invariants live in. A trampoline with no task affinity of its own has nothing
 * to corrupt: it reads one URI, takes a permission, and is gone.
 *
 * Theme.NoDisplay, so nothing of this is ever seen - the player appears as if
 * it had been launched directly.
 */
class ExternalPlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = resolveUri(intent)
        if (uri == null) {
            logcat(LogPriority.WARN) { "EXTERNAL_PLAYBACK no playable uri in ${intent.action}" }
            toast(AYMR.strings.no_available_videos)
            finish()
            return
        }

        takePersistablePermission(uri)

        // Routed through MainActivity rather than straight at PlayerActivity,
        // because MainActivity is where the player overlay lives
        // (PlayerOverlayHost) and is the path every in-app open already takes -
        // see PLAYER_OVERLAY_MIGRATION. Submitting the request before launching
        // means the overlay host picks it up as soon as it composes, whether
        // MainActivity was already running or is starting cold for this.
        PlayerMediaHolder.requestPlayback(
            PlaybackRequest.External(
                uri = uri.toString(),
                title = intent.getStringExtra(Intent.EXTRA_TITLE)
                    ?: intent.getStringExtra("title"),
            ),
        )

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    /**
     * The video URI this intent carries, from whichever field its action uses.
     *
     * ACTION_SEND puts it in EXTRA_STREAM; ACTION_VIEW uses the data URI. Both
     * arrive here because both are registered in the manifest - "open with" and
     * "share to" are the two ways a file manager or gallery offers a player.
     */
    private fun resolveUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    /**
     * Asks to keep read access to [uri] beyond this launch.
     *
     * The grant that arrives with the intent dies with this Activity, and the
     * player outlives it by design - the service-owned session survives the
     * Activity being destroyed entirely (see PlayerMediaHolder), so a grant
     * scoped to this Activity would be gone by the time background playback
     * resumed the file.
     *
     * Best-effort on purpose: a one-off grant from ACTION_SEND is usually not
     * persistable at all and throws here, and the single-launch grant is still
     * enough to start playing. Failing the whole open over a permission that
     * only matters later would trade a working video for a correct lifetime.
     */
    private fun takePersistablePermission(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            logcat(LogPriority.INFO, e) { "EXTERNAL_PLAYBACK uri permission not persistable" }
        }
    }
}
// <-- AM (EXTERNAL_PLAYBACK)
