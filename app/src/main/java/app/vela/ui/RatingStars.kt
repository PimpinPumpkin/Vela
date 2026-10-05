package app.vela.ui

import app.vela.ui.icons.Sym

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Gold used for rating stars throughout the app. */
val StarGold = Color(0xFFF5B400)

/** Google-style status color: green when open, amber when closing/opening soon,
 *  red when closed/temporarily/permanently. [openNow] comes from parseOpenNow's
 *  per-language keyword table over the STATUS TEXT (closed words checked first; the
 *  once-assumed numeric status code was disproven 2026-07-04, see CLAUDE.md), so the
 *  color is right in every language; the English prefix checks below are the fallback
 *  when it's absent. Green requires an affirmative signal AND no contradiction: a
 *  wrongly-true [openNow] must never paint text that literally reads closed
 *  ("Closed ⋅ Opens 5 AM") green - and "Opens …" ≠ "Open"/"Open 24 hours" (the prefix
 *  hole that greened a closed place). Dark-theme pastels sampled off Google Maps:
 *  #FF5449 closed-red (a plain red: the pastel pink did not read as closed at a glance), #6ED58B open-green. */
/** The status color for the theme in use: the pastels below are for dark surfaces and wash out
 *  on white, where the full-strength red and green are used instead. */
@Composable
fun themedStatusColor(status: String, openNow: Boolean? = null): Color {
    val c = placeStatusColor(status, openNow)
    if (app.vela.ui.theme.isAppInDarkTheme()) return c
    return when (c) { Color(0xFFFF5449) -> Color(0xFFD93025); Color(0xFF6ED58B) -> Color(0xFF1E8E3E); Color(0xFFE8A100) -> Color(0xFFB06000); else -> c }
}

fun placeStatusColor(status: String, openNow: Boolean? = null): Color {
    val s = status.trim()
    val textSaysClosed = s.startsWith("Closed") || s.startsWith("Opens") || s.startsWith("Opening") ||
        s.startsWith("Temporarily") || s.startsWith("Permanently")
    return when {
        s.contains("soon", ignoreCase = true) -> Color(0xFFE8A100)
        openNow == false -> Color(0xFFFF5449)
        openNow == true && !textSaysClosed -> Color(0xFF6ED58B)
        textSaysClosed -> Color(0xFFFF5449)
        s.startsWith("Open") || s.startsWith("Closes") -> Color(0xFF6ED58B)
        else -> Color(0xFFFF5449)
    }
}

/** Google-style status line: the head ("Open"/"Closed"/"Closes soon") wears the
 *  status color, everything after the separator ("· Closes 10 p.m.") reads dim
 *  gray. Single-segment lines ("Open 24 hours") stay fully colored. */
@Composable
fun StatusText(
    status: String,
    openNow: Boolean? = null,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    dim: Color = Color.Gray,
    modifier: Modifier = Modifier,
) {
    val head = status.substringBefore("·").trim()
    val tail = status.substringAfter("·", "").trim()
    val headColor = themedStatusColor(status, openNow)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = headColor, fontWeight = fontWeight)) {
                append(head)
            }
            if (tail.isNotEmpty()) {
                append(" · ")
                withStyle(SpanStyle(color = dim)) { append(tail) }
            }
        },
        style = style,
        modifier = modifier,
    )
}

/**
 * Five stars filled to match [rating] (0..5), rounded to the nearest half. Uses
 * the matching Star / StarHalf / StarBorder glyphs so a partial star renders
 * cleanly (the earlier clip-overlay approach drew a slightly-larger filled star
 * over the outline — the "star inside a star" artifact).
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
                halves >= i * 2 -> Sym.Star
                halves >= i * 2 - 1 -> Sym.StarHalf
                else -> Sym.StarBorder
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
