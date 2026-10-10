package aniyomi.source.js

import android.content.Context
import aniyomi.core.common.jsengine.JsNetworkSpike
import aniyomi.core.common.jsengine.JsSafetySpike
import aniyomi.core.common.jsengine.JsSpike
import eu.kanade.tachiyomi.data.backup.create.creators.JsProjectsBackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.JsProjectsRestorer
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager

/**
 * Every spike, behind one button.
 *
 * Nine entries in a settings menu was nine entries of scaffolding for a feature
 * that is finished; they are kept rather than deleted because each one is the
 * only record of what was measured and what the answer was, and a claim nobody
 * can re-run is a comment. Run together they are a regression check: the
 * boundary cost, the abort path, the id derivation, the backup round trip and
 * the line mapping all get re-verified in one press after any change to the
 * engine.
 *
 * Not free to run. Two of these deliberately abandon a thread spinning in
 * JavaScript - that is what they are testing - so each press costs two cores
 * until the app is killed. It is a debug button pressed on purpose, which is the
 * only reason that is acceptable.
 */
class JsSpikes(
    private val context: Context,
    private val network: NetworkHelper,
    private val repository: JsProjectRepository,
    private val sourceManager: SourceManager,
) {

    /** Returns a one-line verdict; the detail is in logcat under each spike's tag. */
    suspend fun run(): String {
        val failures = mutableListOf<String>()

        STAGES.forEach { stage ->
            try {
                logcat(LogPriority.INFO) { "JsSpikes: running $stage" }
                when (stage) {
                    PERF -> JsSpike().run()
                    SAFETY -> JsSafetySpike().run()
                    NETWORK -> JsNetworkSpike(network).run()
                    ADAPTER -> JsAdapterSpike(network).run()
                    STORE -> JsProjectSpike(context, network).run()
                    REGISTRATION -> JsRegistrationSpike(repository, sourceManager).run()
                    BACKUP -> JsBackupSpike(
                        repository = repository,
                        creator = JsProjectsBackupCreator(repository),
                        restorer = JsProjectsRestorer(repository),
                    ).run()
                    WATCHDOG -> JsWatchdogSpike(network).run()
                    EDITOR -> JsEditorSpike(repository, network).run()
                }
            } catch (e: CancellationException) {
                // Not a failure: the caller went away. Reporting it as one sent
                // us looking for a bug in the engine when the only thing that
                // had happened was a screen leaving composition.
                throw e
            } catch (e: Exception) {
                // One spike failing is a result, not a reason to skip the rest.
                logcat(LogPriority.ERROR, e) { "JsSpikes: $stage threw" }
                failures += stage
            }
        }

        return if (failures.isEmpty()) {
            "All ${STAGES.size} JS spikes ran, see logcat"
        } else {
            "${failures.size} of ${STAGES.size} threw: ${failures.joinToString()}"
        }
    }

    private companion object {
        const val PERF = "perf"
        const val SAFETY = "safety"
        const val NETWORK = "network"
        const val ADAPTER = "adapter"
        const val STORE = "store"
        const val REGISTRATION = "registration"
        const val BACKUP = "backup"
        const val WATCHDOG = "watchdog"
        const val EDITOR = "editor"

        /** Cheapest and most self-contained first, so a break shows up early. */
        val STAGES = listOf(
            PERF, SAFETY, NETWORK, ADAPTER, STORE, REGISTRATION, BACKUP, WATCHDOG, EDITOR,
        )
    }
}
