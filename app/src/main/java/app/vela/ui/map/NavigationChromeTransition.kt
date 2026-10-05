package app.vela.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.vela.ui.PageTransitions

/** Animate chrome only; MapView and the guidance session remain live across mode changes. */
@Composable
internal fun NavigationChromeTransition(state: MapUiState, content: @Composable BoxScope.(MapUiState) -> Unit) {
    val motion = PageTransitions.enabled.value
    AnimatedContent(
        targetState = state,
        contentKey = { it.navigating },
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val enter = if (motion) fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 12 } else EnterTransition.None
            val exit = if (motion) fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 12 } else ExitTransition.None
            (enter togetherWith exit).using(sizeTransform = null)
        },
        label = "navigation chrome",
    ) { snapshot ->
        val interaction = if (snapshot.navigating == state.navigating) Modifier else Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        Box(Modifier.fillMaxSize().then(interaction)) { content(snapshot) }
    }
}
