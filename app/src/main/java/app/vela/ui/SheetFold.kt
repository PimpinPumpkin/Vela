package app.vela.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * A section of a bottom sheet that folds with the sheet's own animated height: its height and
 * alpha are `fraction()` of natural. The caller derives the fraction from the same Animatable
 * that drives the sheet, so the fold tracks the sheet's travel frame-for-frame - a slow drag
 * folds it WITH the finger, and there is no second animation clock to fall out of step with
 * the height spring (a tween-clocked exit next to a spring glide read as staccato; user
 * 2026-07-11). Top-anchored and clipped, so it collapses upward and the content below slides
 * over it. Used by the place sheet's minimize (photos / hours / tabs sections) and the results
 * sheet's filter-chip row.
 *
 * `composed=false` removes the content entirely - flip it once the sheet settles where the
 * section is fully hidden, so zero-height controls stay out of D-pad focus search.
 *
 * fraction() is deliberately read ONLY inside the layout and graphicsLayer blocks: a fold
 * frame re-measures the section but never recomposes it (the sheet-height discipline).
 */
@Composable
fun SheetFold(composed: Boolean, fraction: () -> Float, content: @Composable () -> Unit) {
    if (!composed) return
    Column(
        Modifier
            // MODULATE the alpha, do not composite it. A graphicsLayer with alpha < 1 renders its
            // children into an OFFSCREEN buffer and blends that, which on a 4a cost 20 ms of GPU
            // per frame - half the frame budget - and was the "stuttery expand/minimize" report
            // (measured 2026-09-16: total frame p50 32.1 ms with the offscreen, 16.7 ms without).
            // ModulateAlpha applies the alpha to each draw op instead, no buffer. Safe here
            // because the folded content is a column of rows that do not overlap each other;
            // overlapping children would show through one another under this strategy.
            .graphicsLayer {
                clip = true
                alpha = fraction()
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * fraction()).roundToInt()
                layout(placeable.width, h) { placeable.placeRelative(0, 0) }
            },
    ) { content() }
}
