package app.vela.ui.place

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vela.core.model.AboutSection
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import coil.compose.AsyncImage
import app.vela.ui.RatingStars
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.placeStatusColor
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Google-like, fixed sheet palette — independent of the Material You wallpaper
// tint so the name/time/address always read crisp (white-on-dark / black-on-white)
// like Google Maps, instead of a washed-out dynamic tone.
private val SheetDark = Color(0xFF1F1F1F)
private val SheetLight = Color(0xFFFFFFFF)
private val InkDark = Color(0xFFE8EAED)   // primary text in dark mode
private val InkLight = Color(0xFF202124)  // primary text in light mode
private val DimDark = Color(0xFF9AA0A6)   // secondary text in dark mode
private val DimLight = Color(0xFF5F6368)  // secondary text in light mode

@Composable
fun PlaceSheet(
    place: Place,
    route: Route?,
    isSaved: Boolean,
    currentMode: TravelMode,
    reviews: List<Review> = emptyList(),
    reviewsLoading: Boolean = false,
    onClose: () -> Unit,
    onToggleSave: () -> Unit,
    onModeSelected: (TravelMode) -> Unit,
    onDirections: () -> Unit,
    onStartNav: () -> Unit,
    onSteps: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = if (dark) InkDark else InkLight
    val dim = if (dark) DimDark else DimLight
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 110.dp.toPx() }
    Card(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .pointerInput(Unit) {
                // Swipe the sheet down past a threshold to dismiss; otherwise snap back.
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        if (offsetY.value > dismissPx) onClose()
                        else scope.launch { offsetY.animateTo(0f) }
                    },
                )
            },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) SheetDark else SheetLight),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 10.dp)) {
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(dim.copy(alpha = 0.5f)),
                )
            }
            // Business photo strip (Google-style) — horizontally scrollable.
            if (place.photoUrls.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(place.photoUrls) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 152.dp, height = 110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dim.copy(alpha = 0.2f)),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = dim)
                }
            }

            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                place.rating?.let { r ->
                    Text(
                        String.format(Locale.US, "%.1f", r),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                    )
                    RatingStars(r, modifier = Modifier.padding(horizontal = 4.dp))
                    place.reviewCount?.let {
                        Text(
                            "($it)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = dim,
                        )
                    }
                }
                val rest = listOfNotNull(place.priceText, place.category)
                if (rest.isNotEmpty()) {
                    Text(
                        (if (place.rating != null) "   ·   " else "") + rest.joinToString("   ·   "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                    )
                }
            }
            place.statusText?.let { status ->
                // Google colours the status word (Open/Closed) and keeps the time
                // in the normal ink colour: "**Open** · Closes 9 PM".
                val parts = status.split(Regex("\\s*[·⋅]\\s*"), limit = 2)
                val annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = placeStatusColor(status), fontWeight = FontWeight.Bold)) {
                        append(parts[0])
                    }
                    if (parts.size > 1) {
                        withStyle(SpanStyle(color = ink)) { append("  ·  ${parts[1]}") }
                    }
                }
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Google-style quick-action row: Call / Website / Save / Share.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                place.phone?.let { ph ->
                    SheetAction(Icons.Default.Call, "Call", dim) {
                        val dialable = "tel:" + ph.filter { it.isDigit() || it == '+' }
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(dialable))) }
                    }
                }
                place.website?.let { site ->
                    SheetAction(Icons.Default.Language, "Website", dim) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                    }
                }
                SheetAction(
                    if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isSaved) "Saved" else "Save",
                    dim,
                    onClick = onToggleSave,
                )
                ShareAction(place, dim)
            }

            place.address?.let { addr ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        addr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("address", addr))
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy address", tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            PlaceTabs(place, reviews, reviewsLoading, ink, dim)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TravelMode.DRIVE to "Drive",
                    TravelMode.WALK to "Walk",
                    TravelMode.BICYCLE to "Bike",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { onModeSelected(mode) },
                        label = { Text(label) },
                    )
                }
            }

            route?.let { r ->
                Spacer(Modifier.height(12.dp))
                val eta = formatDuration(r.durationInTrafficSeconds ?: r.durationSeconds)
                val label = "$eta  ·  ${formatDistance(r.distanceMeters)}" +
                    if (r.hasLiveTraffic) "  ·  live traffic" else ""
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (r.hasLiveTraffic) MaterialTheme.colorScheme.primary else ink,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (route == null) {
                    Button(onClick = onDirections, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Directions")
                    }
                } else {
                    Button(onClick = onStartNav, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Start")
                    }
                    onSteps?.let {
                        OutlinedButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Steps")
                        }
                    }
                }
            }
        }
    }
}

/** Google-style Overview / Reviews / About tabs. Only tabs with content show;
 *  the content area is height-capped and scrolls (e.g. the reviews list). */
@Composable
private fun PlaceTabs(
    place: Place,
    reviews: List<Review>,
    reviewsLoading: Boolean,
    ink: Color,
    dim: Color,
) {
    val hasOverview = place.hours.isNotEmpty() || place.featuredReview != null
    val hasReviews = place.rating != null || reviews.isNotEmpty() || reviewsLoading
    val hasAbout = place.about.isNotEmpty()
    val tabs = buildList {
        if (hasOverview) add("Overview")
        if (hasReviews) add("Reviews")
        if (hasAbout) add("About")
    }
    if (tabs.isEmpty()) return
    var sel by remember(place.id) { mutableIntStateOf(0) }
    val selected = sel.coerceIn(0, tabs.lastIndex)

    Column(Modifier.padding(top = 8.dp)) {
        TabRow(
            selectedTabIndex = selected,
            containerColor = Color.Transparent,
            contentColor = ink,
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = i == selected, onClick = { sel = i }, text = { Text(title) })
            }
        }
        Column(
            Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp),
        ) {
            when (tabs[selected]) {
                "Overview" -> OverviewTab(place, ink, dim)
                "Reviews" -> ReviewsTab(place, reviews, reviewsLoading, ink, dim)
                "About" -> AboutTab(place.about, ink, dim)
            }
        }
    }
}

@Composable
private fun OverviewTab(place: Place, ink: Color, dim: Color) {
    Column {
        place.featuredReview?.let { rev ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.FormatQuote, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(rev, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = ink, modifier = Modifier.weight(1f))
            }
        }
        if (place.hours.isNotEmpty()) {
            HoursSection(place.hours, ink, dim)
        } else if (place.category != null) {
            Text("Hours not listed", style = MaterialTheme.typography.bodySmall, color = dim)
        }
    }
}

@Composable
private fun ReviewsTab(place: Place, reviews: List<Review>, loading: Boolean, ink: Color, dim: Color) {
    Column {
        place.rating?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(String.format(Locale.US, "%.1f", r), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.width(8.dp))
                RatingStars(r)
                place.reviewCount?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("$it reviews", style = MaterialTheme.typography.bodyMedium, color = dim)
                }
            }
        }
        when {
            loading && reviews.isEmpty() -> Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading reviews…", style = MaterialTheme.typography.bodyMedium, color = dim)
            }
            reviews.isEmpty() -> Text("No reviews available.", style = MaterialTheme.typography.bodyMedium, color = dim)
            else -> reviews.forEach { ReviewRow(it, ink, dim) }
        }
    }
}

@Composable
private fun ReviewRow(review: Review, ink: Color, dim: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (review.authorPhoto != null) {
                AsyncImage(
                    model = review.authorPhoto,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(dim.copy(alpha = 0.2f)),
                )
            } else {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(dim.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) { Text(review.author.take(1), style = MaterialTheme.typography.bodyMedium, color = ink) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(review.author, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RatingStars(review.rating.toDouble(), starSize = 12.dp)
                    review.relativeTime?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                }
            }
        }
        review.text?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AboutTab(sections: List<AboutSection>, ink: Color, dim: Color) {
    Column {
        sections.forEach { sec ->
            Text(
                sec.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = dim,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            sec.items.forEach { item ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = dim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium, color = ink)
                }
            }
        }
    }
}

/** One circular icon-button + label in the quick-action row (Google style). */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    labelColor: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
    ) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
    }
}

/** Share action: opens a small menu — a Google Maps link, raw coordinates, or
 *  just the address. */
@Composable
private fun ShareAction(place: Place, labelColor: Color) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val lat = place.location.lat
    val lng = place.location.lng

    fun share(text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Share place",
                ),
            )
        }
        open = false
    }

    Box {
        SheetAction(Icons.Default.Share, "Share", labelColor) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Google Maps link") },
                onClick = { share("${place.name}\nhttps://www.google.com/maps/search/?api=1&query=$lat%2C$lng") },
            )
            DropdownMenuItem(
                text = { Text("Coordinates") },
                onClick = { share("$lat, $lng") },
            )
            place.address?.let { addr ->
                DropdownMenuItem(
                    text = { Text("Address") },
                    onClick = { share("${place.name}\n$addr") },
                )
            }
        }
    }
}

/** Collapsible weekly hours. Collapsed shows today's range; expanded lists the
 *  week with today in bold. [hours] entries are "Day: range" starting today. */
@Composable
private fun HoursSection(hours: List<String>, ink: Color, dim: Color) {
    var expanded by remember { mutableStateOf(false) }
    val days = remember(hours) {
        hours.map {
            val i = it.indexOf(": ")
            if (i < 0) listOf(it, "") else listOf(it.substring(0, i), it.substring(i + 2))
        }
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = dim,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Hours",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = ink,
                modifier = Modifier.weight(1f),
            )
            if (!expanded) {
                days.firstOrNull()?.let {
                    Text(it[1], style = MaterialTheme.typography.bodyMedium, color = dim)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse hours" else "Expand hours",
                tint = dim,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 26.dp, top = 2.dp, bottom = 2.dp)) {
                days.forEachIndexed { i, dt ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            dt[0],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == 0) ink else dim,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            dt[1],
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == 0) ink else dim,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
