package app.vela.ui.place

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import app.vela.ui.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vela.core.model.AboutSection
import app.vela.core.model.Place
import app.vela.core.model.ShortcutKind
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitStep
import app.vela.core.model.TravelMode
import coil.compose.AsyncImage
import app.vela.ui.RatingStars
import app.vela.ui.SheetPalette
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.placeStatusColor
import java.util.Locale

// Google-like, fixed sheet palette — independent of the Material You wallpaper
// tint so the name/time/address always read crisp (white-on-dark / black-on-white)
// like Google Maps, instead of a washed-out dynamic tone.
// The sheet palette is shared app-wide (see ui/SheetPalette) so the place sheet,
// directions panel, route chooser and steps list all match.
private val SheetDark = SheetPalette.Dark
private val SheetLight = SheetPalette.Light
private val InkDark = SheetPalette.InkDark
private val InkLight = SheetPalette.InkLight
private val DimDark = SheetPalette.DimDark
private val DimLight = SheetPalette.DimLight

@Composable
fun PlaceSheet(
    place: Place,
    isSaved: Boolean,
    reviews: List<Review> = emptyList(),
    reviewsLoading: Boolean = false,
    detailsLoading: Boolean = false,
    placesHere: List<Place> = emptyList(),
    onClose: () -> Unit,
    onToggleSave: () -> Unit,
    onDirections: () -> Unit,
    onOpenPlace: (Place) -> Unit = {},
    onOpenSimilar: (app.vela.core.model.SimilarPlace) -> Unit = {},
    onSetShortcut: (ShortcutKind) -> Unit = {},
    onRetryReviews: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isAppInDarkTheme()
    val ink = if (dark) InkDark else InkLight
    val dim = if (dark) DimDark else DimLight
    // A tapped photo opens the full-screen gallery; resets when the sheet switches place.
    var galleryStart by remember(place.id) { mutableStateOf<Int?>(null) }

    // The place card PEEKS at ~half-screen (so business info isn't immediately
    // full-screen); drag the handle up to expand for the reviews.
    val expandedState = remember(place.id) { mutableStateOf(false) }
    val screenH = LocalConfiguration.current.screenHeightDp
    val maxSheetHeight by animateDpAsState(
        if (expandedState.value) (screenH * 0.92f).dp else (screenH * 0.56f).dp,
        label = "placeSheetHeight",
    )
    // Swipe down ANYWHERE on the sheet to dismiss (not just the handle): a nested-
    // scroll handler watches the body — when it's at the top, a downward drag first
    // collapses an expanded sheet, then dismisses it. Upward / mid-list drags scroll.
    val bodyScroll = rememberScrollState()
    val onCloseUpdated = rememberUpdatedState(onClose)
    val dismissConn = remember(place.id) {
        object : NestedScrollConnection {
            private var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && bodyScroll.value == 0) {
                    acc += available.y
                    when {
                        expandedState.value && acc > 90f -> { expandedState.value = false; acc = 0f }
                        !expandedState.value && acc > 150f -> { acc = 0f; onCloseUpdated.value() }
                    }
                    return available
                }
                if (available.y < 0f) acc = 0f
                return Offset.Zero
            }
        }
    }
    Card(
        modifier.fillMaxWidth().heightIn(max = maxSheetHeight),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) SheetDark else SheetLight),
    ) {
        // Card background fills to the screen bottom; pad the content up off the nav bar.
        Column(Modifier.navigationBarsPadding()) {
            // Drag the handle UP to expand (reviews), DOWN to shrink, down again to dismiss.
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy },
                            onDragEnd = {
                                when {
                                    total < -40f -> expandedState.value = true
                                    total > 40f && expandedState.value -> expandedState.value = false
                                    total > 40f -> onClose()
                                }
                            },
                        )
                    }
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(dim.copy(alpha = 0.5f)),
                )
            }
            Column(
                Modifier
                    .nestedScroll(dismissConn)
                    .verticalScroll(bodyScroll)
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
            // Photo hero at the top (Google-style) — always visible, even at the
            // peek height / in landscape; tap one to open the full gallery.
            if (place.photoUrls.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(place.photoUrls) { i, url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Photo ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 152.dp, height = 110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dim.copy(alpha = 0.2f))
                                .clickable { galleryStart = i },
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
                // Overflow: pin this place straight to Home/Work (Google-style).
                var headerMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { headerMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = dim)
                    }
                    DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Set as Home") },
                            onClick = { headerMenu = false; onSetShortcut(ShortcutKind.HOME) },
                        )
                        DropdownMenuItem(
                            text = { Text("Set as Work") },
                            onClick = { headerMenu = false; onSetShortcut(ShortcutKind.WORK) },
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = dim)
                }
            }

            if (place.rating != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Google leads with a bold rating number; keep it prominent.
                    Text(
                        String.format(Locale.US, "%.1f", place.rating),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                    )
                    RatingStars(place.rating!!, modifier = Modifier.padding(horizontal = 5.dp))
                    place.reviewCount?.let {
                        Text("($it)", style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                }
            }
            // Distance (when the place came from a located search) + price +
            // category on their own line so a long category ("Hamburger restaurant")
            // doesn't wrap mid-word next to the stars; ellipsised if huge.
            val rest = listOfNotNull(
                place.distanceMeters?.let { formatDistance(it) },
                place.priceText,
                place.category,
            )
            if (rest.isNotEmpty()) {
                Text(
                    rest.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (place.permanentlyClosed) {
                // Dead POI — call it out clearly (Google-style red) even when Google
                // sent no hours/status string at all (which is what "no hours" looked
                // like before we parsed this).
                Text(
                    "Permanently closed",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD93838),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            place.statusText?.takeIf { !place.permanentlyClosed }?.let { status ->
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
                Text(annotated, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            place.address?.let { addr ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(addr, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("address", addr))
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy address", tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Hours sit above the action buttons (Directions/Call/…), per request.
            // A permanently-closed POI already says so in red above — don't also
            // nag "Hours not listed" beneath it (the dead-POI hours are moot).
            if (place.hours.isNotEmpty()) {
                HoursSection(place.hours, ink, dim)
            } else if (place.category != null && !place.permanentlyClosed) {
                Text("Hours not listed", style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 10.dp))
            }

            // Quick-action row — Directions (primary) + Call / Website / Save / Share,
            // spread evenly across the width so the last (Share) isn't clipped.
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SheetAction(Icons.Default.Directions, "Directions", dim, emphasized = true, modifier = Modifier.weight(1f), onClick = onDirections)
                place.phone?.let { ph ->
                    SheetAction(Icons.Default.Call, "Call", dim, modifier = Modifier.weight(1f)) {
                        val dialable = "tel:" + ph.filter { it.isDigit() || it == '+' }
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(dialable))) }
                    }
                }
                place.website?.let { site ->
                    SheetAction(Icons.Default.Language, "Website", dim, modifier = Modifier.weight(1f)) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                    }
                }
                SheetAction(
                    if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isSaved) "Saved" else "Save",
                    dim,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleSave,
                )
                ShareAction(place, dim, modifier = Modifier.weight(1f))
            }

            // Action link (Book online / Reserve a table / Order online) — Google shows this
            // as a prominent button. Rendered only when the parse found a real URL + label.
            if (place.actionUrl != null && !place.actionLabel.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                        .clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.actionUrl))) }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        place.actionLabel!!,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Attribute highlights (Google-style chips) — the most useful items from About
            // (service options, offerings, accessibility…), surfaced on the overview for
            // quick scanning instead of being buried in the tab. Filled by the detail fetch.
            val highlights = remember(place.about) { attributeHighlights(place.about) }
            if (highlights.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    highlights.forEach { h ->
                        Text(
                            h,
                            style = MaterialTheme.typography.labelLarge,
                            color = ink,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(dim.copy(alpha = 0.12f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Popular times sit BELOW the action buttons (Google's order). Lazily
            // filled by the WebView detail fetch, so it pops in a beat after open.
            place.popularTimes?.let { PopularTimesSection(it, ink, dim) }
            // While the (slow, ~10–20 s) detail fetch is in flight and popular times
            // haven't landed yet, show a subtle indicator so it reads as "loading", not
            // "missing" — it clears to the chart, or to nothing if this place has none.
            if (place.popularTimes == null && detailsLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = dim)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading popular times & details…", style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
            // (The editorial summary + "From the owner" blurb live in the About tab.)

            // Other Google listings at the same spot (a co-branded shop's duplicate
            // profile, or a different unit at the address) — like Google's "Also at
            // this location". Tap to open one.
            if (placesHere.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Also at this location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
                placesHere.forEach { other ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPlace(other) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(other.name, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                other.rating?.let { String.format(Locale.US, "%.1f★", it) + (other.reviewCount?.let { n -> " ($n)" } ?: "") },
                                other.category,
                            ).joinToString("  ·  ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open", tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // "People also search for" — related places (Google-style). Filled by the
            // detail re-fetch (root [2][11][0]); a horizontal row of tappable cards.
            if (place.similarPlaces.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("People also search for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    place.similarPlaces.forEach { s ->
                        Column(
                            Modifier.width(150.dp).clip(RoundedCornerShape(12.dp))
                                .background(dim.copy(alpha = 0.10f))
                                .clickable { onOpenSimilar(s) }
                                .padding(12.dp),
                        ) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            s.rating?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(String.format(Locale.US, "%.1f★", it), style = MaterialTheme.typography.bodySmall, color = dim)
                            }
                        }
                    }
                }
            }

            PlaceTabs(place, reviews, reviewsLoading, onRetryReviews, ink, dim)
            }
        }
    }

    galleryStart?.let { start ->
        PhotoGallery(place.photoUrls, place.photoDates, start) { galleryStart = null }
    }
}

/**
 * The directions preview — a dedicated bottom panel (not buried in the place
 * sheet) that opens when you tap "Directions": destination header, travel-mode
 * tabs, the route option(s) with traffic-aware ETAs (alternates are selectable),
 * and a prominent Start. Transit shows the results board instead.
 */
@Composable
fun DirectionsPanel(
    originName: String,
    destinationName: String,
    onEditOrigin: (() -> Unit)? = null,
    onEditDestination: (() -> Unit)? = null,
    onSwap: () -> Unit,
    currentMode: TravelMode,
    routes: List<Route>,
    activeRoute: Route?,
    transit: List<TransitItinerary>,
    transitLoading: Boolean,
    onModeSelected: (TravelMode) -> Unit,
    onSelectRoute: (Int) -> Unit,
    onStartNav: () -> Unit,
    onSteps: (() -> Unit)?,
    onSearchAlongRoute: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val ink = if (dark) InkDark else InkLight
    val dim = if (dark) DimDark else DimLight
    // Keyed to the destination so opening directions for a different place starts
    // expanded again instead of inheriting the previous session's collapsed state.
    val collapsed = remember(destinationName) { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) SheetDark else SheetLight),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            // Drag handle — swipe down to minimise the chooser (peek the route on the
            // map before you Start), swipe up or tap to bring it back.
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy ->
                            if (dy > 6f) collapsed.value = true else if (dy < -6f) collapsed.value = false
                        }
                    }
                    .clickable { collapsed.value = !collapsed.value }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(dim.copy(alpha = 0.4f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // The "From" row — tappable to route from a different place (Google
                    // shows an edit affordance; here a pencil + accent text when editable).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (onEditOrigin != null) {
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable { onEditOrigin() }.padding(vertical = 2.dp)
                        } else Modifier,
                    ) {
                        Icon(Icons.Default.TripOrigin, contentDescription = null, tint = dim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            originName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (onEditOrigin != null) MaterialTheme.colorScheme.primary else dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onEditOrigin != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Edit, contentDescription = "Change starting point", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    // The "To" row — editable in the same way as "From", used when the
                    // route is *reversed* (then the custom endpoint is the destination).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (onEditDestination != null) {
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable { onEditDestination() }.padding(vertical = 2.dp)
                        } else Modifier,
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            destinationName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onEditDestination != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Edit, contentDescription = "Change destination", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                IconButton(onClick = onSwap) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap start and destination", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close directions", tint = dim) }
            }
            AnimatedVisibility(visible = !collapsed.value) {
              Column {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TravelMode.DRIVE to "Drive",
                    TravelMode.TRANSIT to "Transit",
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
            if (currentMode == TravelMode.TRANSIT) {
                TransitBoard(transit, transitLoading, ink, dim, dark)
            } else {
                Spacer(Modifier.height(12.dp))
                if (routes.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Finding the best route…", style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        routes.forEachIndexed { i, r ->
                            val selected = r === activeRoute || (activeRoute == null && i == 0)
                            RouteOption(r, selected, fastest = i == 0, dark = dark, ink = ink, dim = dim) { onSelectRoute(i) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DepartTimeChooser(activeRoute ?: routes.firstOrNull(), dim)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Spacer(Modifier.height(14.dp))
                    Text("Search along route", style = MaterialTheme.typography.labelMedium, color = dim)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "Gas" to Icons.Default.LocalGasStation,
                            "Food" to Icons.Default.Restaurant,
                            "Coffee" to Icons.Default.LocalCafe,
                            "Groceries" to Icons.Default.LocalGroceryStore,
                        ).forEach { (label, icon) ->
                            FilterChip(
                                selected = false,
                                onClick = { onSearchAlongRoute(label) },
                                label = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
              }
            }
            // Minimised: keep a Start button reachable without expanding.
            if (collapsed.value) {
                Button(
                    onClick = onStartNav,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Start")
                }
            }
        }
    }
}

/** "Leave now / Depart at / Arrive by" chooser. "Leave now" uses the live
 *  traffic-aware duration; a future "Depart at" / "Arrive by" uses Google's own
 *  *typical* best→worst spread (`Route.typicalRangeSeconds`, from summary[10][4])
 *  to show an honest arrival/leave **window** rather than a false-precision single
 *  time — Google's per-departure prediction needs a login/app-only request field
 *  we can't reach keyless, so we surface the range Google itself plans with. Falls
 *  back to a single ~estimate when no range is shipped (short trips, walk/bike). */
@Composable
private fun DepartTimeChooser(route: Route?, dim: Color) {
    val context = LocalContext.current
    // Keyed to the route so switching places/alternates resets the picked time
    // instead of carrying it over.
    var mode by remember(route?.summary) { mutableStateOf(0) } // 0 = now, 1 = depart at, 2 = arrive by
    var picked by remember(route?.summary) { mutableStateOf<java.time.LocalTime?>(null) }
    val nowDur = route?.let { it.durationInTrafficSeconds ?: it.durationSeconds } ?: 0.0
    val range = route?.typicalRangeSeconds
    val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)

    fun openPicker(target: Int) {
        val base = picked ?: java.time.LocalTime.now()
        android.app.TimePickerDialog(
            context,
            { _, h, m -> picked = java.time.LocalTime.of(h, m); mode = target },
            base.hour, base.minute, false,
        ).show()
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Leave now") })
        FilterChip(selected = mode == 1, onClick = { openPicker(1) }, label = { Text("Depart at") })
        FilterChip(selected = mode == 2, onClick = { openPicker(2) }, label = { Text("Arrive by") })
    }

    // A clock window [base+loOffset .. base+hiOffset] when we have a typical spread,
    // else a single ~point from the current duration (sign chosen by the caller).
    fun window(base: java.time.LocalTime, lo: Double, hi: Double, sign: Int): String =
        if (range != null)
            "${base.plusSeconds((sign * lo).toLong()).format(fmt)}–${base.plusSeconds((sign * hi).toLong()).format(fmt)}"
        else "~${base.plusSeconds((sign * nowDur).toLong()).format(fmt)}"

    val lo = range?.first ?: nowDur
    val hi = range?.second ?: nowDur
    val (summary, note) = when (mode) {
        1 -> picked?.let { p ->
            "Depart ${p.format(fmt)}  ·  arrive ${window(p, lo, hi, +1)}" to
                (if (range != null) "in typical traffic" else "based on current traffic")
        } ?: (null to null)
        2 -> picked?.let { p ->
            // Arrive by p → leave between p−hi and p−lo (earlier end first).
            "Arrive by ${p.format(fmt)}  ·  leave ${window(p, hi, lo, -1)}" to
                (if (range != null) "in typical traffic" else "based on current traffic")
        } ?: (null to null)
        else -> {
            val arrive = "Arrive ~${java.time.LocalTime.now().plusSeconds(nowDur.toLong()).format(fmt)}"
            arrive to (range?.let { "Usually ${formatDuration(it.first)} – ${formatDuration(it.second)}" } ?: "current traffic")
        }
    }
    summary?.let {
        Column(Modifier.padding(top = 6.dp)) {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = dim)
            note?.let { n -> Text(n, style = MaterialTheme.typography.bodySmall, color = dim) }
        }
    }
}

/** One route choice in the directions panel: a traffic-coloured ETA + distance/
 *  via, highlighted when it's the active one, with a "Fastest" tag on the first. */
@Composable
private fun RouteOption(r: Route, selected: Boolean, fastest: Boolean, dark: Boolean, ink: Color, dim: Color, onClick: () -> Unit) {
    val eta = formatDuration(r.durationInTrafficSeconds ?: r.durationSeconds)
    val etaColor = trafficEtaColor(r) ?: ink
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    else SheetPalette.row(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(eta, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = etaColor)
                if (fastest) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Fastest",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val sub = listOfNotNull(
                formatDistance(r.distanceMeters),
                r.summary?.takeIf { it.isNotBlank() }?.let { "via $it" },
                if (r.hasLiveTraffic) "live traffic" else null,
            ).joinToString("  ·  ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = dim)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** ETA colour by congestion when live traffic is known: green free-flowing →
 *  amber → red. Null when there's no live-traffic signal (use the ink colour). */
private fun trafficEtaColor(r: Route): Color? = r.trafficRatio?.let {
    when {
        it > 1.4 -> SheetPalette.TrafficRed
        it > 1.15 -> SheetPalette.TrafficAmber
        else -> SheetPalette.TrafficGreen
    }
}

/** The transit results board — Google's first transit view: a list of departure
 *  options, each a time window + total duration + the coloured line pills you
 *  ride. Fed by the keyless WebView fetch ([app.vela.web.WebDirectionsFetcher]). */
@Composable
private fun TransitBoard(
    trips: List<TransitItinerary>,
    loading: Boolean,
    ink: Color,
    dim: Color,
    dark: Boolean,
) {
    Spacer(Modifier.height(10.dp))
    when {
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Finding transit routes…", style = MaterialTheme.typography.bodyMedium, color = dim)
        }
        trips.isEmpty() -> Text("No transit routes found", style = MaterialTheme.typography.bodyMedium, color = dim)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            trips.take(6).forEach { TransitRow(it, ink, dim, dark) }
        }
    }
}

@Composable
private fun TransitRow(t: TransitItinerary, ink: Color, dim: Color, dark: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = t.steps.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SheetPalette.row(dark))
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val range = listOfNotNull(t.departureText, t.arrivalText).joinToString(" – ")
            Text(
                range.ifEmpty { t.durationText.orEmpty() },
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                modifier = Modifier.weight(1f),
            )
            if (range.isNotEmpty()) {
                t.durationText?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = dim) }
            }
            if (canExpand) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide steps" else "Show steps",
                    tint = dim,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                )
            }
        }
        if (t.lines.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                t.lines.take(4).forEachIndexed { i, line ->
                    if (i > 0) Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = dim,
                        modifier = Modifier.size(14.dp),
                    )
                    LinePill(line)
                }
            }
        }
        val sub = listOfNotNull(t.distanceText, t.agency).joinToString("  ·  ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = dim)
        if (expanded) {
            HorizontalDivider(color = dim.copy(alpha = 0.25f))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                t.steps.forEach { TransitStepRow(it, ink, dim) }
            }
        }
    }
}

/** One leg in the expanded drill-down: a mode glyph + the line/"Walk" title and a
 *  times·duration·distance subtitle ("Bus 42B / 5:48 AM – 6:41 AM · 53 min"). */
@Composable
private fun TransitStepRow(s: TransitStep, ink: Color, dim: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            transitModeIcon(s.mode),
            contentDescription = null,
            tint = s.line?.colorHex?.let { parseHexColor(it) } ?: dim,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column {
            Text(s.line?.name ?: "Walk", style = MaterialTheme.typography.bodyMedium, color = ink)
            val parts = listOfNotNull(
                if (s.departText != null && s.arriveText != null) "${s.departText} – ${s.arriveText}" else null,
                s.durationText,
                s.distanceText,
            )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = dim)
            }
        }
    }
}

/** A colour-filled line badge (e.g. a blue "Amtrak Thruway"), mirroring Google's
 *  transit pills; falls back to the theme primary when no colour is supplied. */
@Composable
private fun LinePill(line: TransitLine) {
    val fallback = MaterialTheme.colorScheme.primary
    val bg = parseHexColor(line.colorHex) ?: fallback
    val fg = parseHexColor(line.textColorHex) ?: if (bg.luminance() > 0.5f) Color(0xFF202124) else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(transitModeIcon(line.mode), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(line.name, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

private fun transitModeIcon(mode: TransitMode) = when (mode) {
    TransitMode.BUS -> Icons.Default.DirectionsBus
    TransitMode.TRAM -> Icons.Default.Tram
    TransitMode.SUBWAY -> Icons.Default.DirectionsSubway
    TransitMode.TRAIN -> Icons.Default.Train
    TransitMode.FERRY -> Icons.Default.DirectionsBoat
    TransitMode.WALK -> Icons.Default.DirectionsWalk
    TransitMode.GENERIC -> Icons.Default.DirectionsTransit
}

/** Parse a CSS hex colour ("#rrggbb" / "#rgb"); null if absent/malformed. */
private fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    return runCatching {
        when (h.length) {
            6 -> Color(("FF$h").toLong(16))
            8 -> Color(h.toLong(16))
            3 -> Color(("FF" + h.map { "$it$it" }.joinToString("")).toLong(16))
            else -> null
        }
    }.getOrNull()
}

/** Full-screen, swipeable photo viewer (tap a photo in the strip to open). */
@Composable
private fun PhotoGallery(urls: List<String>, dates: List<String?>, start: Int, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, urls.lastIndex)) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                // One gesture loop so pinch-zoom, pan-when-zoomed, swipe-down-to-
                // dismiss, AND the pager's horizontal swipe between photos all work
                // together: a pinch or a pan-while-zoomed consumes the pointers (so
                // the pager stays put), a clearly-downward drag at 1× drives the
                // dismiss, and a flat horizontal drag is left UNCONSUMED so the
                // HorizontalPager pages. (Stacking two detectors stole both.)
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var dismissY by remember { mutableStateOf(0f) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var dx = 0f
                                var dy = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    dx += pan.x; dy += pan.y
                                    when {
                                        zoom != 1f || scale > 1f -> {
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            offset = if (scale > 1f) offset + pan else Offset.Zero
                                            dismissY = 0f
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                        dy > 0f && dy > kotlin.math.abs(dx) -> {
                                            dismissY = dy
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (scale <= 1f) {
                                    if (dismissY > 240f) onDismiss()
                                    dismissY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = urls[page].atWidth(1280),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y + dismissY,
                                alpha = (1f - dismissY / 1000f).coerceIn(0.4f, 1f),
                            ),
                    )
                }
            }
            Text(
                "${pager.currentPage + 1} / ${urls.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            )
            // "Photo · May 2026" when the gallery RPC gave a posted date (Google-style).
            dates.getOrNull(pager.currentPage)?.let { posted ->
                Text(
                    "Photo · $posted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** Re-size a Google FIFE photo URL (…=w500-h350) to a target width for full view. */
private fun String.atWidth(w: Int): String = replace(Regex("=w\\d+(-h\\d+)?.*$"), "=w$w")

/** Reviews / About tabs. Only tabs with content show; the content area is
 *  height-capped and scrolls (e.g. the reviews list). */
@Composable
private fun PlaceTabs(
    place: Place,
    reviews: List<Review>,
    reviewsLoading: Boolean,
    onRetryReviews: () -> Unit,
    ink: Color,
    dim: Color,
) {
    val hasReviews = place.rating != null || reviews.isNotEmpty() || reviewsLoading || place.featuredReview != null
    val hasAbout = place.about.isNotEmpty() || place.editorialSummary != null || place.ownerDescription != null
    val tabs = buildList {
        if (hasReviews) add("Reviews")
        if (hasAbout) add("About")
    }
    if (tabs.isEmpty()) return
    var sel by remember(place.id) { mutableIntStateOf(0) }
    val selected = sel.coerceIn(0, tabs.lastIndex)

    Column(Modifier.padding(top = 12.dp)) {
        TabRow(
            selectedTabIndex = selected,
            containerColor = Color.Transparent,
            contentColor = ink,
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = i == selected, onClick = { sel = i }, text = { Text(title) })
            }
        }
        Column(Modifier.padding(top = 10.dp)) {
            when (tabs[selected]) {
                "Reviews" -> ReviewsTab(place, reviews, reviewsLoading, onRetryReviews, ink, dim)
                "About" -> AboutTab(place.about, place.editorialSummary, place.ownerDescription, ink, dim)
            }
        }
    }
}

@Composable
private fun ReviewsTab(place: Place, reviews: List<Review>, loading: Boolean, onRetry: () -> Unit, ink: Color, dim: Color) {
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
        place.featuredReview?.let { rev ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.FormatQuote, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(rev, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = ink, modifier = Modifier.weight(1f))
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
            // The count says this place HAS reviews but we have none — the RPC flaked (it's
            // intermittent), so this is a load FAILURE, not a review-less place. Say so and
            // let the user retry instead of lying with "No reviews available."
            reviews.isEmpty() && (place.reviewCount ?: 0) > 0 -> Row(
                Modifier.fillMaxWidth().clickable { onRetry() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Couldn't load reviews. Tap to retry.", style = MaterialTheme.typography.bodyMedium, color = dim)
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
        // User-attached review photos (Google-style thumbnail strip).
        if (review.photos.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                review.photos.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // Subtle fill so the slot isn't a transparent gap while the
                        // thumbnail loads (or if it fails).
                        modifier = Modifier.size(104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(dim.copy(alpha = 0.12f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutTab(
    sections: List<AboutSection>,
    editorialSummary: String?,
    ownerDescription: String?,
    ink: Color,
    dim: Color,
) {
    Column {
        // Google's editorial one-liner first, then the owner's "From the owner" blurb,
        // then the attribute sections — the description before the rest, per request.
        editorialSummary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(bottom = 4.dp))
        }
        ownerDescription?.let {
            Text("From the owner", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = dim, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(bottom = 4.dp))
        }
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

/** One circular icon-button + label in the quick-action row (Google style).
 *  [emphasized] gives the primary filled treatment (used for Directions). */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    labelColor: Color,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        if (emphasized) {
            FilledIconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        } else {
            FilledTonalIconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Share action: opens a small menu — a Google Maps link, a keyless geo: pin
 *  (opens in any maps app, incl. Vela), raw coordinates, or just the address. */
@Composable
private fun ShareAction(place: Place, labelColor: Color, modifier: Modifier = Modifier) {
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

    Box(modifier) {
        SheetAction(Icons.Default.Share, "Share", labelColor) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Google Maps link") },
                onClick = { share("${place.name}\nhttps://www.google.com/maps/search/?api=1&query=$lat%2C$lng") },
            )
            // A geo: URI opens in ANY maps app (incl. Vela) — no google.com, the
            // degoogled-friendly way to send a pin.
            DropdownMenuItem(
                text = { Text("Map pin (geo:)") },
                onClick = { share("${place.name}\ngeo:$lat,$lng?q=$lat,$lng(${Uri.encode(place.name)})") },
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

/** Google-style "popular times": day chips + an hourly busyness bar chart, today's
 *  current hour highlighted. */
@Composable
private fun PopularTimesSection(pt: app.vela.core.model.PopularTimes, ink: Color, dim: Color) {
    val accent = MaterialTheme.colorScheme.primary
    val today = remember { java.time.LocalDate.now().dayOfWeek.value } // 1=Mon..7=Sun
    val currentHour = remember { java.time.LocalTime.now().hour }
    // Keyed to pt so a different place's histogram resets the selected day (instead of
    // carrying over the day tapped on the previous place). firstOrNull guards an empty
    // days list — the `day` lookup below also returns early if nothing matches.
    var selectedDow by remember(pt) {
        mutableStateOf(
            if (pt.days.any { it.dayOfWeek == today }) today
            else pt.days.firstOrNull()?.dayOfWeek ?: today,
        )
    }
    val day = pt.days.firstOrNull { it.dayOfWeek == selectedDow } ?: return
    val isToday = selectedDow == today
    val nowOcc = if (isToday) day.hours.firstOrNull { it.hour == currentHour }?.occupancy else null

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Popular times", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            pt.days.forEach { d ->
                val sel = d.dayOfWeek == selectedDow
                Text(
                    java.time.DayOfWeek.of(d.dayOfWeek)
                        .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sel) accent else dim,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(CircleShape).clickable { selectedDow = d.dayOfWeek }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (nowOcc != null) {
            Text("${busynessLabel(nowOcc)} right now", style = MaterialTheme.typography.bodySmall, color = dim)
        }
        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp)) {
            val hrs = day.hours
            if (hrs.isEmpty()) return@Canvas
            val bw = size.width / hrs.size
            hrs.forEachIndexed { i, h ->
                val bh = (h.occupancy / 100f).coerceIn(0.03f, 1f) * size.height
                val now = isToday && h.hour == currentHour
                drawRect(
                    color = if (now) accent else dim.copy(alpha = 0.3f),
                    topLeft = Offset(i * bw + bw * 0.12f, size.height - bh),
                    size = Size(bw * 0.76f, bh),
                )
            }
        }
        val hrs = day.hours
        if (hrs.size >= 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(hrs.first(), hrs[hrs.size / 2], hrs.last()).forEach {
                    Text(hourLabel(it.hour), style = MaterialTheme.typography.labelSmall, color = dim)
                }
            }
        }
    }
}

private fun busynessLabel(occ: Int): String = when {
    occ < 20 -> "Not busy"
    occ < 40 -> "Not too busy"
    occ < 60 -> "A little busy"
    occ < 85 -> "Usually busy"
    else -> "Very busy"
}

private fun hourLabel(h: Int): String = when {
    h == 0 -> "12a"
    h < 12 -> "${h}a"
    h == 12 -> "12p"
    else -> "${h - 12}p"
}

/** The handful of attribute items worth showing as overview chips — the categories users
 *  scan for first, a few items each, deduped and capped. (Full set stays in the About tab.) */
private fun attributeHighlights(about: List<AboutSection>): List<String> {
    if (about.isEmpty()) return emptyList()
    val priority = listOf(
        "Service options", "Dining options", "Offerings", "Highlights",
        "Popular for", "Amenities", "Accessibility", "Atmosphere", "Planning",
    )
    return about
        .sortedBy { s -> priority.indexOf(s.title).let { if (it < 0) priority.size else it } }
        .flatMap { it.items }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(6)
}

@Composable
private fun HoursSection(hours: List<String>, ink: Color, dim: Color) {
    var expanded by remember { mutableStateOf(false) }
    val days = remember(hours) {
        hours.map {
            val i = it.indexOf(": ")
            if (i < 0) listOf(it, "") else listOf(it.substring(0, i), it.substring(i + 2))
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
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
