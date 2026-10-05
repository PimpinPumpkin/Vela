package app.vela.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalSheetActive = androidx.compose.runtime.compositionLocalOf { true }

/** Retains the outgoing snapshot until it has slid away, including after model data is cleared. */
@Composable
internal fun <T> SheetTransition(
    targetState: T,
    contentKey: (T) -> Any?,
    animateContent: (T) -> Boolean,
    onBack: (() -> Unit)? = null,
    animateChange: (T, T) -> Boolean = { _, _ -> true },
    content: @Composable BoxScope.(T) -> Unit,
) {
    val enabled = PageTransitions.enabled.value
    val key = contentKey(targetState)
    val gesture = remember(key) { SheetBackProgress() }
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    val latestKey by rememberUpdatedState(key)
    AnimatedContent(
        targetState = SheetFrame(targetState, gesture),
        contentKey = { contentKey(it.content) },
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val from = initialState
            val to = this.targetState
            val animate = enabled && animateChange(from.content, to.content)
            val enter = if (animate && animateContent(to.content)) {
                slideInVertically(tween(250)) { it }
            } else EnterTransition.None
            val exit = if (animate && !from.gesture.committed && animateContent(from.content)) {
                slideOutVertically(tween(200)) { it }
            } else ExitTransition.None
            (enter togetherWith exit).using(sizeTransform = null)
        },
        label = "sheet",
    ) { frame ->
        val snapshot = frame.content
        val snapshotGesture = frame.gesture
        val current = contentKey(snapshot) == key && !snapshotGesture.committed
        // A closing sheet is only a visual preview; it must not accept a second close or tap.
        val interaction = if (current) Modifier else Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationY = size.height * snapshotGesture.fraction.value
        }.then(interaction), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSheetActive provides current) {
                content(snapshot)
            }
        }
    }
    // After the content so this owns back ahead of the sheet's ordinary BackHandler.
    PredictiveBackHandler(enabled = onBack != null && !gesture.committed) { events ->
        gesture.resetJob?.cancel()
        consumeSheetBack(
            progress = events.map { it.progress },
            update = { if (enabled) gesture.fraction.snapTo(it) },
            commit = {
                if (enabled) gesture.fraction.animateTo(1f, tween(160))
                // An external navigation event may have replaced the sheet during the gesture.
                val close = latestBack
                if (latestKey == key && close != null) {
                    gesture.committed = true
                    close()
                } else {
                    gesture.fraction.snapTo(0f)
                }
            },
            cancel = {
                gesture.resetJob = scope.launch { gesture.fraction.animateTo(0f, tween(180)) }
            },
        )
    }
}

private class SheetBackProgress {
    val fraction = Animatable(0f)
    var committed by mutableStateOf(false)
    var resetJob: Job? = null
}

private data class SheetFrame<T>(val content: T, val gesture: SheetBackProgress)
