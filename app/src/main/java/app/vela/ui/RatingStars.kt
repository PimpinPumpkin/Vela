package app.vela.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Gold used for rating stars throughout the app. */
val StarGold = Color(0xFFF5B400)

/** Google-style status colour: green when open, amber when closing/opening soon,
 * red when closed/temporarily/permanently. [openNow] (from Google's locale-independent
 * numeric status code) drives the colour so it's right in EVERY language; the English
 * text checks are a fallback for when the code is absent (keeps en behaviour identical).
 * Green requires an affirmative signal AND no contradiction: a wrongly-true [openNow]
 * must never paint text that literally reads closed ("Closed ⋅ Opens 5 AM") green -
 * and "Opens …" ≠ "Open"/"Open 24 hours" (the prefix hole that greened a closed place). */
fun placeStatusColor(status: String, openNow: Boolean? = null): Color {
    val s = status.trim()
    val textSaysClosed = s.startsWith("Closed") || s.startsWith("Opens") || s.startsWith("Opening") ||
        s.startsWith("Temporarily") || s.startsWith("Permanently")
    return when {
        s.contains("soon", ignoreCase = true) -> Color(0xFFE8A100)
        openNow == false -> Color(0xFFD93025)
        openNow == true && !textSaysClosed -> Color(0xFF1E8E3E)
        textSaysClosed -> Color(0xFFD93025)
        s.startsWith("Open") || s.startsWith("Closes") -> Color(0xFF1E8E3E)
        else -> Color(0xFFD93025)
    }
}

/**
 * Five stars filled to match [rating] (0..5), rounded to the nearest half. Uses
 * the matching Star / StarHalf / StarBorder glyphs so a partial star renders
 * cleanly (the earlier clip-overlay approach drew a slightly-larger filled star
 * over the outline - the "star inside a star" artifact).
 */
@Composable
fun RatingStars(
    rating: Double,
    modifier: Modifier = Modifier,
    starSize: Dp = 15.dp,
) {
    val halves = (rating * 2).roundToInt() // rating rounded to nearest 0.5, in half-units
    Row(modifier) {
        for (i in 1..5) {
            val icon = when {
                halves >= i * 2 -> Icons.Filled.Star
                halves >= i * 2 - 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                icon,
                contentDescription = null,
                tint = StarGold,
                modifier = Modifier.size(starSize),
            )
        }
    }
}
