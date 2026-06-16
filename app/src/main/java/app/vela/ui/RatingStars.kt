package app.vela.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gold used for rating stars throughout the app. */
val StarGold = Color(0xFFF5B400)

/** Google-style status colour: green when open, amber when closing/opening soon,
 *  red when closed/temporarily/permanently. */
fun placeStatusColor(status: String): Color = when {
    status.contains("soon", ignoreCase = true) -> Color(0xFFE8A100)
    status.startsWith("Open") || status.startsWith("Closes") -> Color(0xFF1E8E3E)
    else -> Color(0xFFD93025)
}

/**
 * Five stars filled to match [rating] (0..5) with smooth fractional fill on the
 * partial star — the Google-Maps look. Each star is a gold outline with a gold
 * filled star clipped over its left [fill] fraction, so it only needs the
 * always-present Star / StarBorder icons (no material-icons-extended dependency).
 */
@Composable
fun RatingStars(
    rating: Double,
    modifier: Modifier = Modifier,
    starSize: Dp = 15.dp,
) {
    Row(modifier) {
        for (i in 0 until 5) {
            val fill = (rating - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(starSize)) {
                Icon(
                    Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = StarGold,
                    modifier = Modifier.size(starSize),
                )
                if (fill > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .clipToBounds(),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = StarGold,
                            modifier = Modifier.size(starSize),
                        )
                    }
                }
            }
        }
    }
}
