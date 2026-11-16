// AY -->
package tachiyomi.domain.custombutton.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.custombutton.repository.CustomButtonRepository

@Inject
class RestoreCustomButtons(
    private val customButtonRepository: CustomButtonRepository,
) {
    suspend fun await() = withNonCancellableContext {
        customButtonRepository.restoreDefaultCustomButtons()
    }
}
// <-- AY
