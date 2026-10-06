package app.vela.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Do not mutate navigation until the system completes the gesture, including button back. */
internal suspend fun consumeSheetBack(
    progress: Flow<Float>,
    update: suspend (Float) -> Unit,
    commit: suspend () -> Unit,
    cancel: () -> Unit,
) {
    try {
        progress.collect { update(it.coerceIn(0f, 1f)) }
        commit()
    } catch (e: CancellationException) {
        cancel()
        throw e
    }
}
