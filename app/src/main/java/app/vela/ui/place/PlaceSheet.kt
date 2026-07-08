package app.vela.ui.place

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Streetview
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
// D-pad-only operation (docs/dpad.md) — one import block so upstream merges stay clean.
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadFieldEscape
import app.vela.ui.rememberDpadAutoFocus // D-pad-first initial focus (docs/dpad.md)
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
import androidx.compose.ui.res.stringResource
import app.vela.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
import kotlin.math.roundToInt
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
    reviewsFound: Int = 0,
    photosLoading: Boolean = false,
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
    // Gallery category filter (null = All); resets per place. Chips appear only when Google tagged photos.
    var photoCat by remember(place.id) { mutableStateOf<String?>(null) }

    // Three detents, Google-style: EXPANDED (reviews) ↔ PEEK (default, ~half) ↔ MINIMIZED (a small
    // card). A gentle swipe down steps one detent (expanded→peek→minimized); from minimized another
    // swipe dismisses, and a big/fast swipe dismisses outright. So the first gentle pull minimizes
    // instead of closing. expandedState stays the reviews driver; minimizedState is only ever set from
    // peek, so the two are never both true.
    val expandedState = remember(place.id) { mutableStateOf(false) }
    val minimizedState = remember(place.id) { mutableStateOf(false) }
    val screenH = LocalConfiguration.current.screenHeightDp
    val maxSheetHeight by animateDpAsState(
        when {
            expandedState.value -> (screenH * 0.92f).dp
            minimizedState.value -> (screenH * 0.26f).dp
            else -> (screenH * 0.56f).dp
        },
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
            // One detent step per gesture — lift and swipe again for the next. A single long drag
            // steps down once (e.g. peek→minimized) and stops, so it can't blow through to dismiss.
            private var steppedThisGesture = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && bodyScroll.value == 0) {
                    acc += available.y
                    if (!steppedThisGesture) {
                        when {
                            // Collapse an expanded sheet a touch sooner (common gesture); the other
                            // steps want a more deliberate pull so a gentle swipe doesn't overshoot.
                            expandedState.value && acc > 90f -> { expandedState.value = false; steppedThisGesture = true; acc = 0f }
                            !minimizedState.value && acc > 150f -> { minimizedState.value = true; steppedThisGesture = true; acc = 0f }
                            minimizedState.value && acc > 150f -> { steppedThisGesture = true; acc = 0f; onCloseUpdated.value() }
                        }
                    }
                    return available
                }
                // Scrolling INTO the content (dragging up / content moving up) grows the sheet to
                // full height first, Google-style — so reading down the POI info expands it without
                // reaching for the handle. Doesn't consume the scroll: the body scrolls too.
                if (available.y < 0f) {
                    acc = 0f
                    if (minimizedState.value) minimizedState.value = false
                    else if (!expandedState.value) expandedState.value = true
                }
                return Offset.Zero
            }
            // The fling phase runs at the end of every drag (even at zero velocity) — use it as the
            // gesture boundary that re-arms stepping for the next swipe. A hard downward flick from
            // peek/minimized dismisses outright (the "dramatic swipe closes" case); an expanded flick
            // just collapses to peek via onPreScroll above.
            override suspend fun onPreFling(available: Velocity): Velocity {
                val dramatic = available.y > 2400f && bodyScroll.value == 0 && !expandedState.value
                acc = 0f
                steppedThisGesture = false
                if (dramatic) onCloseUpdated.value()
                return Velocity.Zero
            }
        }
    }
    // Scroll-sync with the live reviews panel: the panel forwards boundary drags (reviews at
    // their top + finger down, or bottom + up) as raw deltas; we scroll the sheet body 1:1 with
    // the finger, and past the body's own ends mirror dismissConn (collapse → dismiss on a pull
    // past the top; expand on a push past the bottom). pull[0]/pull[1] accumulate those
    // overshoots; any real body movement resets them (same feel as dismissConn's acc).
    val scope = rememberCoroutineScope()
    // [0]=pull-down overshoot, [1]=push-up overshoot, [2]=collapsed-this-gesture guard (0/1) so one
    // continuous down-drag collapses but can't also dismiss (matches dismissConn).
    val pull = remember(place.id) { floatArrayOf(0f, 0f, 0f) }
    // True while the user is reading reviews "full screen" (panel engaged): set by the panel's
    // engagement signal, cleared when they drag back toward the sheet top. Hides the native
    // histogram so the panel gets the height.
    val reviewsEngaged = remember(place.id) { mutableStateOf(false) }
    val onPanelOverscroll: (Float) -> Unit = { dy ->
        val consumed = bodyScroll.dispatchRawDelta(-dy)
        val leftover = -dy - consumed
        when {
            leftover < -0.5f -> { // pulling down past the body top
                pull[1] = 0f
                pull[0] += -leftover
                if (pull[2] == 0f) {
                    when {
                        expandedState.value && pull[0] > 90f -> { expandedState.value = false; reviewsEngaged.value = false; pull[0] = 0f; pull[2] = 1f }
                        !minimizedState.value && pull[0] > 150f -> { minimizedState.value = true; reviewsEngaged.value = false; pull[0] = 0f; pull[2] = 1f }
                        minimizedState.value && pull[0] > 150f -> { pull[0] = 0f; pull[2] = 1f; reviewsEngaged.value = false; onCloseUpdated.value() }
                    }
                }
            }
            leftover > 0.5f -> { // pushing up past the body bottom
                pull[0] = 0f
                pull[1] += leftover
                if (pull[1] > 90f) {
                    if (minimizedState.value) minimizedState.value = false
                    else if (!expandedState.value) expandedState.value = true
                    pull[1] = 0f
                }
            }
            else -> { pull[0] = 0f; pull[1] = 0f }
        }
    }
    val onPanelOverscrollEnd: (Float) -> Unit = { velocityY ->
        pull[0] = 0f; pull[1] = 0f; pull[2] = 0f
        // Disengage at GESTURE END, not per-pixel: re-inserting the header content (rating +
        // histogram + tabs) mid-drag shifts the layout right under the held finger — flicker.
        // Fires when the body walked up OR is simply at/near its top — in engaged mode the
        // panel fills the sheet, so the body's whole range is tiny and a "walked 150px" test
        // could NEVER pass (engaged got stuck forever; the header never came back).
        if (bodyScroll.value <= 1 || bodyScroll.value < bodyScroll.maxValue - 150) reviewsEngaged.value = false
        // Carry a boundary fling into the sheet so it glides instead of dead-stopping at
        // finger-up. velocityY is finger px/s (+down); scroll space is inverted.
        if (kotlin.math.abs(velocityY) > 600f) {
            scope.launch { bodyScroll.animateScrollBy(-velocityY * 0.3f) }
        }
    }
    // The user started really scrolling the reviews panel: slide the sheet to full screen around
    // them, Google-style (expand + settle the body so the panel fills the viewport). The second
    // animateScrollTo chases the body's max as the expand animation grows it.
    val onPanelEngaged: () -> Unit = {
        reviewsEngaged.value = true
        scope.launch {
            expandedState.value = true
            bodyScroll.animateScrollTo(bodyScroll.maxValue)
            bodyScroll.animateScrollTo(bodyScroll.maxValue)
        }
    }
    Card(
        modifier.fillMaxWidth().heightIn(max = maxSheetHeight),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) SheetDark else SheetLight),
    ) {
        // Card background fills to the screen bottom; pad the content up off the nav bar.
        Column(Modifier.navigationBarsPadding()) {
            // D-pad-first (docs/dpad.md): when the sheet opens, land focus ON the handle so the
            // sheet is the active surface — otherwise Compose leaves focus on the search bar
            // behind the sheet (measured: sometimes the search field, sometimes a photo — the
            // exact nondeterminism to kill). No-op under touch.
            val sheetAutoFocus = rememberDpadAutoFocus()
            // Drag the handle UP to expand (reviews), DOWN to shrink, down again to dismiss.
            // TAP toggles expand/peek. The touch target is a tall (36dp) invisible strip — the
            // 4dp handle is just the visual; a fat hit-area makes it easy to grab.
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(sheetAutoFocus)
                    // D-pad (docs/dpad.md): the handle is a real button — focusable, OK runs the
                    // same one-detent-grow logic as a tap. clickable replaces the tap-only
                    // detector (same behaviour under touch); the drag detector below is untouched.
                    .dpadHighlight(RoundedCornerShape(3.dp))
                    .clickable {
                        // Grows one detent: minimized→peek, peek→expanded, expanded→peek.
                        if (minimizedState.value) minimizedState.value = false
                        else expandedState.value = !expandedState.value
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy },
                            onDragEnd = {
                                when {
                                    // Swipe up grows one detent.
                                    total < -40f -> {
                                        if (minimizedState.value) minimizedState.value = false
                                        else expandedState.value = true
                                    }
                                    // A big deliberate swipe down closes outright ("dramatic swipe").
                                    total > 220f -> onClose()
                                    // A gentle swipe down shrinks one detent (expanded→peek→minimized→close).
                                    total > 40f -> when {
                                        expandedState.value -> expandedState.value = false
                                        !minimizedState.value -> minimizedState.value = true
                                        else -> onClose()
                                    }
                                }
                            },
                        )
                    }
                    .heightIn(min = 36.dp)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(dim.copy(alpha = 0.6f)),
                )
            }
            Column(
                Modifier
                    .nestedScroll(dismissConn)
                    .verticalScroll(bodyScroll)
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
            // Minimized detent: a compact card (name, rating, Directions) instead of the full body,
            // like Google's collapsed sheet. At this small height leading with the photo hero showed
            // only photos AND let the horizontal gallery swallow dismiss drags, so short-circuit here.
            if (minimizedState.value) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (place.rating != null) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format(Locale.US, "%.1f", place.rating),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                        )
                        RatingStars(place.rating!!, modifier = Modifier.padding(horizontal = 5.dp))
                        place.reviewCount?.let { Text("($it)", style = MaterialTheme.typography.bodySmall, color = dim) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ActionPill(Icons.Default.Directions, stringResource(R.string.place_directions), emphasized = true, onClick = onDirections)
                return@Column
            }
            // Photo hero at the top (Google-style) — always visible, even at the
            // peek height / in landscape; tap one to open the full gallery.
            if (place.photoUrls.isNotEmpty() || photosLoading) {
                // Category filter chips (Menu / Food & drink / Vibe / By owner …) — only when Google tagged
                // photos with categories, mirroring its gallery tabs. "All" clears the filter.
                val photoCats = remember(place.photoCategories) { place.photoCategories.filterNotNull().distinct() }
                if (photoCats.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (listOf<String?>(null) + photoCats).forEach { cat ->
                            FilterChip(
                                selected = photoCat == cat,
                                onClick = { photoCat = cat },
                                label = { Text(cat ?: stringResource(R.string.place_photo_category_all)) },
                            )
                        }
                    }
                }
                // Indices into the FULL photo list that match the selected category (keeps galleryStart
                // pointing at the right photo in the full-screen viewer).
                val shown = remember(place.photoUrls, place.photoCategories, photoCat) {
                    place.photoUrls.indices.filter { photoCat == null || place.photoCategories.getOrNull(it) == photoCat }
                }
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it }) { i ->
                        AsyncImage(
                            model = place.photoUrls[i],
                            contentDescription = stringResource(R.string.place_photo_number, i + 1),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 152.dp, height = 110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dim.copy(alpha = 0.2f))
                                .clickable { galleryStart = i },
                        )
                    }
                    // The full gallery scrapes in the background a beat after the sheet opens —
                    // pulse placeholder tiles so it reads as "more photos loading", not "done".
                    if (photosLoading) {
                        item { PhotoShimmerTile(dim) }
                        if (place.photoUrls.isEmpty()) {
                            item { PhotoShimmerTile(dim) }
                            item { PhotoShimmerTile(dim) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.name,
                    // titleLarge (22sp) not headlineSmall (24sp) so a longer name ("Starbucks Coffee
                    // Company") fits two lines beside the Save/Share/⋮/✕ icons instead of ellipsising.
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Save + Share as compact header actions (preferred look). The name has weight(1f) and
                // wraps to 2 lines if long, so these stay put without shoving it off.
                IconButton(onClick = onToggleSave, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isSaved) stringResource(R.string.place_saved) else stringResource(R.string.place_save),
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else dim,
                        modifier = Modifier.size(20.dp),
                    )
                }
                ShareIconButton(place, dim)
                // Overflow: pin this place straight to Home/Work (Google-style).
                var headerMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { headerMenu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.place_more_options), tint = dim, modifier = Modifier.size(20.dp))
                    }
                    // D-pad-first (docs/dpad.md): a DropdownMenu opens with NO item focused,
                    // wasting the first arrow press. Focus the first item from the outer scope
                    // once the popup settles. No-op under touch.
                    // D-pad note (docs/dpad.md): a Compose DropdownMenu opens with the popup
                    // window focused but no item pre-highlighted — Compose sets popup item focus
                    // only on the first key event, and NOTHING in-app can pre-place it (8 approaches
                    // proven on-device). Fully navigable: OK opens, DOWN/UP walk, OK selects, BACK
                    // closes. Stock DropdownMenu so touch stays byte-identical.
                    DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.place_set_as_home)) },
                            onClick = { headerMenu = false; onSetShortcut(ShortcutKind.HOME) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.place_set_as_work)) },
                            onClick = { headerMenu = false; onSetShortcut(ShortcutKind.WORK) },
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.place_close), tint = dim, modifier = Modifier.size(20.dp))
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
            // Dropped-pin coordinates — when a tapped/held point did NOT snap to a street address (an
            // arbitrary spot, a bare road, or a failed reverse-geocode), surface the lat/lng PROMINENTLY
            // right under the name, Google-style, alongside the road name we already carry in the address
            // row below. A house-numbered snap ("123 F St") or a real business POI shows its address
            // instead, so this doesn't clutter those. Tappable to copy. Detect the snap by the name's
            // first token being a pure-digit house number (a numbered street like "128th St" keeps its
            // "th", so it reads as unsnapped and correctly shows coordinates).
            val isDroppedPin = place.id.startsWith("pin:")
            val snappedToAddress = place.name.substringBefore(' ')
                .let { it.isNotEmpty() && it.all(Char::isDigit) } && place.name.contains(' ')
            if (isDroppedPin && !snappedToAddress) {
                val coords = "%.5f, %.5f".format(Locale.US, place.location.lat, place.location.lng)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = dim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(coords, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("coordinates", coords))
                        Toast.makeText(context, context.getString(R.string.place_coordinates_copied), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.place_copy_coordinates), tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (place.permanentlyClosed) {
                // Dead POI — call it out clearly (Google-style red) even when Google
                // sent no hours/status string at all (which is what "no hours" looked
                // like before we parsed this).
                Text(
                    stringResource(R.string.place_permanently_closed),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD93838),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (place.temporarilyClosed && !place.permanentlyClosed) {
                // Owner-set temporary closure — banner it like Google does, and suppress the ordinary
                // status/hours lines below (an "Opens 11:30 AM Tue" under a temp-closure reads as if the
                // place will open then, which is exactly the misleading state the closure overrides).
                Text(
                    stringResource(R.string.place_temporarily_closed),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD93838),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Google's live status STRING is PRIMARY: it's the only source that knows an owner-set
            // "closed today" (the weekly hours now carry holiday overrides, but not ad-hoc closures).
            // Only when Google gives NO status do we fall back to an Open/Closed computed from the weekly
            // hours (past-midnight-aware) — better than a blank, without trusting stale regular hours
            // over a real closure.
            // Ticks each minute so a sheet left open crosses an open/close boundary instead of showing
            // "Open · Closes 9 PM" forever after 9 PM (the fallback is only used when Google sent no status).
            val nowMinute by produceState(initialValue = java.time.LocalDateTime.now()) {
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    value = java.time.LocalDateTime.now()
                }
            }
            val computedStatus = remember(place.hours, nowMinute) {
                app.vela.core.util.OpeningHours.statusAt(place.hours, nowMinute)
            }
            val statusLine = place.statusText
                ?: computedStatus?.let { (if (it.open) "Open" else "Closed") + " · " + it.detail }
            statusLine?.takeIf { !place.permanentlyClosed && !place.temporarilyClosed }?.let { status ->
                // Google colours the status word (Open/Closed) and keeps the time
                // in the normal ink colour: "**Open** · Closes 9 PM".
                val parts = status.split(Regex("\\s*[·⋅]\\s*"), limit = 2)
                val annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = placeStatusColor(status, place.openNow), fontWeight = FontWeight.Bold)) {
                        append(parts[0])
                    }
                    if (parts.size > 1) {
                        withStyle(SpanStyle(color = ink)) { append("  ·  ${parts[1]}") }
                    }
                }
                Text(annotated, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            // Holiday / special hours callout (Google shows these prominently — e.g. "Independence
            // Day · Hours might differ" — not just buried on one day's row). The parser tags the
            // holiday day's string with " · <label>"; surface the soonest one up top.
            val holiday = remember(place.hours, nowMinute) {
                upcomingHoliday(place.hours, nowMinute.toLocalDate())
            }
            if (!place.permanentlyClosed && !place.temporarilyClosed) holiday?.let { h ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFE0A63C))) {
                            append(h.label)
                        }
                        withStyle(SpanStyle(color = dim)) { append("  ·  ${h.whenLabel} · ${h.hours}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Quick-action pills FIRST — a highlighted Directions + short Call / Website, right under
            // the identity block so Directions is reachable WITHOUT scrolling (Google's order). Save/
            // Share live in the header; the actual phone number / website domain are tappable detail
            // rows lower down (below the hours), out of the way of the primary action.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionPill(Icons.Default.Directions, stringResource(R.string.place_directions), emphasized = true, onClick = onDirections)
                place.phone?.let { ph ->
                    ActionPill(Icons.Default.Call, stringResource(R.string.place_call)) {
                        val dialable = "tel:" + ph.filter { it.isDigit() || it == '+' }
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(dialable))) }
                    }
                }
                place.website?.let { site ->
                    ActionPill(Icons.Default.Language, stringResource(R.string.place_website)) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                    }
                }
                // Street View — opens Google's KEYLESS consumer pano (documented map_action=pano deep
                // link) EXTERNALLY (Google Maps app or the browser). The interactive pano is keyless but
                // renders black in an in-app WebView on some devices (ANGLE GL driver + the SV SPA served
                // a degraded page), so we hand it off rather than embed a maybe-black panel (see ROADMAP).
                ActionPill(Icons.Filled.Streetview, stringResource(R.string.place_street_view)) {
                    val loc = place.location
                    val pano = "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=" +
                        "%.6f,%.6f".format(java.util.Locale.US, loc.lat, loc.lng)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pano))) }
                }
            }

            place.address?.let { addr ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(addr, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("address", addr))
                        Toast.makeText(context, context.getString(R.string.place_address_copied), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.place_copy_address), tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // A permanently-closed POI already says so in red above — don't also
            // nag "Hours not listed" beneath it (the dead-POI hours are moot).
            if (place.hours.isNotEmpty()) {
                HoursSection(place.hours, ink, dim)
            } else if (place.category != null && !place.permanentlyClosed) {
                Text(stringResource(R.string.place_hours_not_listed), style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 10.dp))
            }

            // Phone + website as their own tappable rows showing the actual number / domain — placed
            // BELOW the hours (Google's order), well clear of the Directions button up top. The pills
            // are the fast path; these are the detail for when you want to see/copy the number or URL.
            place.phone?.let { ph ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        val dialable = "tel:" + ph.filter { it.isDigit() || it == '+' }
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(dialable))) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ph, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
                }
            }
            place.website?.let { site ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        runCatching { Uri.parse(site).host?.removePrefix("www.") }.getOrNull() ?: site,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    Text(stringResource(R.string.place_loading_popular_times), style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
            // (The editorial summary + "From the owner" blurb live in the About tab.)

            // Other Google listings at the same spot (a co-branded shop's duplicate
            // profile, or a different unit at the address) — like Google's "Also at
            // this location". Tap to open one.
            if (placesHere.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.place_also_at_location), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.place_open), tint = dim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // "People also search for" — related places (Google-style). Filled by the
            // detail re-fetch (root [2][11][0]); a horizontal row of tappable cards.
            if (place.similarPlaces.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.place_people_also_search), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
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

            PlaceTabs(place, reviews, reviewsLoading, reviewsFound, onRetryReviews, ink, dim, onPanelOverscroll, onPanelOverscrollEnd, onPanelEngaged, reviewsEngaged.value)
            }
        }
    }

    galleryStart?.let { start ->
        PhotoGallery(place.photoUrls, place.photoDates.map { d -> d?.let { context.getString(R.string.place_photo_caption, it) } }, start) { galleryStart = null }
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
    stops: List<String> = emptyList(),
    onAddStop: (() -> Unit)? = null,
    onRemoveStop: (Int) -> Unit = {},
    onMoveStop: (Int, Int) -> Unit = { _, _ -> },
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
                    .dpadHighlight(RoundedCornerShape(3.dp)) // D-pad: OK toggles (docs/dpad.md)
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
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.place_change_start), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    // Intermediate stops (multi-stop), between From and To like Google — each removable,
                    // then an "Add stop" row. Only shown for drive/walk/bike (transit has no waypoints).
                    if (currentMode != TravelMode.TRANSIT) {
                        stops.forEachIndexed { i, stopName ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(dim))
                                Spacer(Modifier.width(11.dp))
                                Text(stopName, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                // Reorder arrows (only with 2+ stops): up unless first, down unless last.
                                // IconButtons (like the Swap/Close controls in this header), NOT raw 18-20dp
                                // clickable Icons — three tiny targets 2dp apart invite remove-instead-of-
                                // reorder mis-taps, and removal re-routes immediately with no undo.
                                if (stops.size > 1) {
                                    if (i > 0) IconButton(onClick = { onMoveStop(i, -1) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.place_move_stop_up), tint = dim, modifier = Modifier.size(20.dp))
                                    }
                                    if (i < stops.size - 1) IconButton(onClick = { onMoveStop(i, 1) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.place_move_stop_down), tint = dim, modifier = Modifier.size(20.dp))
                                    }
                                }
                                IconButton(onClick = { onRemoveStop(i) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.place_remove_stop), tint = dim, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (onAddStop != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onAddStop() }.padding(vertical = 2.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.place_add_stop), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(3.dp))
                        }
                    }
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
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.place_change_destination), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                IconButton(onClick = onSwap) {
                    Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.place_swap_start_destination), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.place_close_directions), tint = dim) }
            }
            AnimatedVisibility(visible = !collapsed.value) {
              // Scrollable + height-capped: with up to 4 alternates the mode tabs + route
              // list + depart options + Start/Steps exceed the screen, and without a scroll
              // container the Start button was pushed off-screen with no way to reach it
              // (measured on-device — affects touch too). Cap to ~58% of the screen so the
              // From/To header stays visible above; focus-to-Start now scrolls it into view.
              val dirScroll = rememberScrollState()
              val dirMaxH = (LocalConfiguration.current.screenHeightDp * 0.58f).dp
              Column(Modifier.heightIn(max = dirMaxH).verticalScroll(dirScroll)) {
            Spacer(Modifier.height(10.dp))
            // D-pad-first (docs/dpad.md): land focus on the first travel-mode tab when the
            // directions panel opens, so it's the active surface (else focus stays on the
            // search bar behind it). No-op under touch.
            val dirAutoFocus = rememberDpadAutoFocus()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TravelMode.DRIVE to stringResource(R.string.place_mode_drive),
                    TravelMode.TRANSIT to stringResource(R.string.place_mode_transit),
                    TravelMode.WALK to stringResource(R.string.place_mode_walk),
                    TravelMode.BICYCLE to stringResource(R.string.place_mode_bike),
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { onModeSelected(mode) },
                        label = { Text(label) },
                        modifier = if (mode == TravelMode.DRIVE) Modifier.focusRequester(dirAutoFocus) else Modifier,
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
                        Text(stringResource(R.string.place_finding_route), style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                } else {
                    // Fastest ETA across the alternates (list is sorted fastest-first, but take the min
                    // so the "+N min" deltas are robust even if two tie) → each slower route shows how much
                    // longer it is, Google-style, so you can weigh the alternates at a glance.
                    val fastestEta = routes.minOf { it.durationInTrafficSeconds ?: it.durationSeconds }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        routes.forEachIndexed { i, r ->
                            val selected = r === activeRoute || (activeRoute == null && i == 0)
                            RouteOption(r, selected, fastestEtaSeconds = fastestEta, dark = dark, ink = ink, dim = dim) { onSelectRoute(i) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DepartTimeChooser(activeRoute ?: routes.firstOrNull(), dim)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onStartNav, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.place_start))
                        }
                        onSteps?.let {
                            OutlinedButton(onClick = it) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.place_steps))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.place_search_along_route), style = MaterialTheme.typography.labelMedium, color = dim)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // (localized label, STABLE English query, icon) — query is the logic key, label localizes.
                        listOf(
                            Triple(R.string.cat_gas, "Gas", Icons.Default.LocalGasStation),
                            Triple(R.string.cat_food, "Food", Icons.Default.Restaurant),
                            Triple(R.string.cat_coffee, "Coffee", Icons.Default.LocalCafe),
                            Triple(R.string.cat_groceries, "Groceries", Icons.Default.LocalGroceryStore),
                        ).forEach { (labelRes, query, icon) ->
                            FilterChip(
                                selected = false,
                                onClick = { onSearchAlongRoute(query) },
                                label = { Text(stringResource(labelRes)) },
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
                    Text(stringResource(R.string.place_start))
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
        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text(stringResource(R.string.place_leave_now)) })
        FilterChip(selected = mode == 1, onClick = { openPicker(1) }, label = { Text(stringResource(R.string.place_depart_at)) })
        FilterChip(selected = mode == 2, onClick = { openPicker(2) }, label = { Text(stringResource(R.string.place_arrive_by)) })
    }

    // A clock window [base+loOffset .. base+hiOffset] when we have a typical spread,
    // else a single ~point from the current duration (sign chosen by the caller).
    fun window(base: java.time.LocalTime, lo: Double, hi: Double, sign: Int): String =
        if (range != null)
            "${base.plusSeconds((sign * lo).toLong()).format(fmt)}–${base.plusSeconds((sign * hi).toLong()).format(fmt)}"
        else "~${base.plusSeconds((sign * nowDur).toLong()).format(fmt)}"

    val lo = range?.first ?: nowDur
    val hi = range?.second ?: nowDur
    // Only claim "current traffic" when the route actually carries a live in-traffic ETA. An offline
    // (GraphHopper) route, or any traffic-less route, has neither a typical range nor live traffic, so
    // it shows no traffic note at all instead of a misleading "current traffic".
    val hasLive = route?.hasLiveTraffic == true
    // Resolve both traffic notes up front (composable calls must be at the top level, not inside a lambda).
    val typicalNote = stringResource(R.string.place_in_typical_traffic)
    val liveNoteDepart = stringResource(R.string.place_based_current_traffic)
    val liveNoteNow = stringResource(R.string.place_current_traffic)
    val departNote = when { range != null -> typicalNote; hasLive -> liveNoteDepart; else -> null }
    val (summary, note) = when (mode) {
        1 -> picked?.let { p ->
            stringResource(R.string.place_depart_arrive, p.format(fmt), window(p, lo, hi, +1)) to departNote
        } ?: (null to null)
        2 -> picked?.let { p ->
            // Arrive by p → leave between p−hi and p−lo (earlier end first).
            stringResource(R.string.place_arriveby_leave, p.format(fmt), window(p, hi, lo, -1)) to departNote
        } ?: (null to null)
        else -> {
            val arrive = stringResource(R.string.place_arrive_approx, java.time.LocalTime.now().plusSeconds(nowDur.toLong()).format(fmt))
            arrive to (range?.let { stringResource(R.string.place_usually_range, formatDuration(it.first), formatDuration(it.second)) }
                ?: if (hasLive) liveNoteNow else null)
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
 *  via, highlighted when it's the active one. The fastest carries a "Fastest" tag; each slower
 *  alternate shows how much longer it is ("+5 min") so the choice is legible at a glance. */
@Composable
private fun RouteOption(r: Route, selected: Boolean, fastestEtaSeconds: Double, dark: Boolean, ink: Color, dim: Color, onClick: () -> Unit) {
    val etaSeconds = r.durationInTrafficSeconds ?: r.durationSeconds
    val eta = formatDuration(etaSeconds)
    val etaColor = trafficEtaColor(r) ?: ink
    // Round to the nearest minute; anything under ~30 s slower is effectively "the same" → still "Fastest".
    val deltaMin = ((etaSeconds - fastestEtaSeconds) / 60.0).roundToInt()
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
                Spacer(Modifier.width(8.dp))
                if (deltaMin <= 0) {
                    Text(
                        stringResource(R.string.place_fastest),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                } else {
                    // "+5 min" vs the fastest — a quiet tag so the fastest still reads as primary.
                    Text(
                        stringResource(R.string.place_delta_min, deltaMin),
                        style = MaterialTheme.typography.labelSmall,
                        color = dim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ink.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val sub = listOfNotNull(
                formatDistance(r.distanceMeters),
                r.summary?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.place_via, it) },
                if (r.hasLiveTraffic) stringResource(R.string.place_live_traffic) else null,
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
            Text(stringResource(R.string.place_finding_transit), style = MaterialTheme.typography.bodyMedium, color = dim)
        }
        trips.isEmpty() -> Text(stringResource(R.string.place_no_transit), style = MaterialTheme.typography.bodyMedium, color = dim)
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
                    contentDescription = if (expanded) stringResource(R.string.place_hide_steps) else stringResource(R.string.place_show_steps),
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
            Text(s.line?.name ?: stringResource(R.string.place_walk), style = MaterialTheme.typography.bodyMedium, color = ink)
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

/** A photo-tile-sized placeholder that gently pulses while the full gallery scrapes in —
 *  signals "more photos loading" at the end of the strip (or fills it when there's no preview yet). */
@Composable
private fun PhotoShimmerTile(base: Color) {
    val transition = rememberInfiniteTransition(label = "photoShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "photoShimmerAlpha",
    )
    Box(
        Modifier
            .size(width = 152.dp, height = 110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(base.copy(alpha = alpha)),
    )
}

/** Full-screen, swipeable photo viewer (tap a photo in the strip to open). */
@Composable
private fun PhotoGallery(urls: List<String>, dates: List<String?>, start: Int, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, urls.lastIndex)) { urls.size }
        // D-pad (docs/dpad.md): the viewer grabs focus so LEFT/RIGHT page through the
        // photos with no touch; BACK already dismisses (Dialog).
        val galleryFocus = remember { FocusRequester() }
        val keyScope = rememberCoroutineScope()
        LaunchedEffect(Unit) { runCatching { galleryFocus.requestFocus() } }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(galleryFocus)
                .onKeyEvent { ev ->
                    val pageKey = ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
                    when {
                        !pageKey -> false
                        ev.type != KeyEventType.KeyUp -> true
                        ev.key == Key.DirectionRight -> {
                            keyScope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; true
                        }
                        else -> {
                            keyScope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; true
                        }
                    }
                }
                .focusable(),
        ) {
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
            Box(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.place_gallery_counter, pager.currentPage + 1, urls.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.place_close), tint = Color.White)
                }
                // Per-photo caption ("Ele Campbell · a year ago" for reviews). On Android 15/16 a Dialog's
                // window insets read ZERO and the bottom ~nav-bar strip is CLIPPED (undrawable) — proven on a
                // Pixel 9 — so a normal bottom caption vanished. A FIXED bottom clearance keeps it in the
                // drawable area regardless (harmlessly a touch higher on phones with no such clip).
                dates.getOrNull(pager.currentPage)?.let { caption ->
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 120.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/** Re-size a Google FIFE photo URL (…=w500-h350) to a target width for full view. */
private fun String.atWidth(w: Int): String = replace(Regex("=w\\d+(-h\\d+)?.*$"), "=w$w")

/** Native search / sort / topic chips for the live reviews panel — Vela's own UI driving the
 *  panel's hidden Google controls (the originals are carved out once the chips arrive). Search
 *  is Google's server-side one (ALL reviews, not just those loaded); chips are Google's
 *  auto-parsed review topics; sort mirrors Google's four orders. */
@Composable
private fun PanelControls(
    chips: List<app.vela.web.PanelChip>?,
    selected: String,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onChip: (String) -> Unit,
    onSort: (String) -> Unit,
    ink: Color,
    dim: Color,
) {
    if (chips == null) return // nothing to control yet — the panel is still booting
    // NOTE: an EMPTY list is meaningful — the business has no auto-parsed topics; search + sort
    // still render (they exist on every panel), only the chip row is skipped.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.place_search_reviews), color = dim) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            shape = RoundedCornerShape(24.dp),
            // No fixed height: OutlinedTextField reserves internal padding for a label line, so a
            // 52dp clamp clipped the text's descenders at the bottom. Natural height doesn't clip.
            modifier = Modifier.weight(1f).dpadFieldEscape(),
        )
        Spacer(Modifier.width(6.dp))
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.place_sort_reviews), tint = dim)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                listOf("Most relevant", "Newest", "Highest rating", "Lowest rating").forEach { o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { sortOpen = false; onSort(o) })
                }
            }
        }
    }
    if (chips.isNotEmpty()) LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        items(chips.size) { i ->
            val c = chips[i]
            FilterChip(
                selected = selected == c.label,
                onClick = { onChip(c.label) },
                label = { Text(if (c.count != null) "${c.label}  ${c.count}" else c.label) },
            )
        }
    }
}

/** Native rating distribution (Google-style amber bars), counts ordered [5★,4★,3★,2★,1★] —
 *  scraped off the live reviews panel so the histogram renders in Vela's own UI. */
@Composable
private fun RatingHistogram(counts: List<Int>, dim: Color, modifier: Modifier = Modifier) {
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        counts.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${5 - i}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    modifier = Modifier.width(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(dim.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(n / max.toFloat())
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF9AB00)),
                    )
                }
            }
        }
    }
}

/** Reviews / About tabs. Only tabs with content show; the content area is
 *  height-capped and scrolls (e.g. the reviews list). */
@Composable
private fun PlaceTabs(
    place: Place,
    reviews: List<Review>,
    reviewsLoading: Boolean,
    reviewsFound: Int,
    onRetryReviews: () -> Unit,
    ink: Color,
    dim: Color,
    onPanelOverscroll: (Float) -> Unit = {},
    onPanelOverscrollEnd: (Float) -> Unit = {},
    onPanelEngaged: () -> Unit = {},
    panelEngaged: Boolean = false,
) {
    // With the live panel on, the scrape never runs, so reviewsLoading can't summon the tab —
    // any Google-listed place (valid feature id) gets the tab; the panel shows Google's own
    // zero-reviews state if there are none.
    val hasReviews = place.rating != null || reviews.isNotEmpty() || reviewsLoading || place.featuredReview != null ||
        (app.vela.ui.LiveReviews.on.value && place.featureId?.contains(":") == true)
    val hasAbout = place.about.isNotEmpty() || place.editorialSummary != null || place.ownerDescription != null
    val tabs = buildList {
        if (hasReviews) add("Reviews")
        if (hasAbout) add("About")
    }
    if (tabs.isEmpty()) return
    var sel by remember(place.id) { mutableIntStateOf(0) }
    val selected = sel.coerceIn(0, tabs.lastIndex)

    Column(Modifier.padding(top = 12.dp)) {
        // In engaged reviews mode the panel takes the WHOLE sheet — no floating tab bar above
        // it (it returns when the user walks the sheet back up and disengages).
        if (!panelEngaged) {
            TabRow(
                selectedTabIndex = selected,
                containerColor = Color.Transparent,
                contentColor = ink,
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = i == selected, onClick = { sel = i }, text = { Text(title) })
                }
            }
        }
        Column(Modifier.padding(top = 10.dp)) {
            when (tabs[selected]) {
                "Reviews" -> {
                    // Inline = the NATIVE scraped list (smooth, no nested WebView, no scroll seam).
                    // Tapping a review photo opens the shared full-screen gallery; a "Read all
                    // reviews" button opens the live Google panel FULL-SCREEN (its own screen, so
                    // no nesting → Google's own infinite scroll + search + native photo/video).
                    val fid = place.featureId
                    var reviewPhotos by remember(place.id) { mutableStateOf<Triple<List<String>, List<String?>, Int>?>(null) }
                    var showFullPanel by remember(place.id) { mutableStateOf(false) }
                    ReviewsTab(
                        place, reviews, reviewsLoading, reviewsFound, onRetryReviews, ink, dim,
                        onPhotoTap = { urls, start, caption ->
                            reviewPhotos = Triple(urls, urls.map { caption }, start)
                        },
                        onReadAll = if (app.vela.ui.LiveReviews.on.value && fid != null && fid.contains(":")) {
                            { showFullPanel = true }
                        } else null,
                    )
                    reviewPhotos?.let { (urls, caps, start) ->
                        PhotoGallery(urls, caps, start) { reviewPhotos = null }
                    }
                    if (showFullPanel && fid != null) {
                        FullScreenReviews(fid, place, ink, dim) { showFullPanel = false }
                    }
                }
                "About" -> AboutTab(place.about, place.editorialSummary, place.ownerDescription, ink, dim)
            }
        }
    }
}

/** The live Google reviews panel, FULL-SCREEN (the "Read all reviews" view). No nesting inside a
 *  scroll → no scroll-sync, no jitter; Google's own infinite scroll, server-side search, and
 *  native photo/video viewers all work. Back / the top-bar arrow closes it back to the sheet. */
@Composable
private fun FullScreenReviews(featureId: String, place: Place, ink: Color, dim: Color, onClose: () -> Unit) {
    val dark = isAppInDarkTheme()
    var reviewPhotos by remember(featureId) { mutableStateOf<Triple<List<String>, List<String?>, Int>?>(null) }
    // Back closes the photo gallery first (if open), else the whole screen.
    BackHandler(onBack = { if (reviewPhotos != null) reviewPhotos = null else onClose() })
    // D-pad-first (docs/dpad.md): land focus on the back arrow immediately so the panel isn't
    // unfocused during the WebView's load window (the WebView itself is alpha-0 + only grabs
    // focus on page-finish). The auto-focus loop stops on its first success, so it hands off
    // to the WebView cleanly once the page loads. No-op under touch.
    val reviewsBackFocus = rememberDpadAutoFocus()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = if (dark) SheetDark else SheetLight, contentColor = ink) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.focusRequester(reviewsBackFocus)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.place_back), tint = ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
                        Text(stringResource(R.string.place_reviews_title), style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                }
                app.vela.web.GoogleReviewsPanel(
                    featureId = featureId,
                    dark = dark,
                    fullScreen = true,
                    modifier = Modifier.fillMaxSize(),
                    onFailed = onClose, // can't carve (throttle / markup drift) → bounce back; the inline native list is still there
                    // Tapping a review photo opens Vela's own gallery (Google's photo viewer is a
                    // page-nav the lockdown blocks + the carve can't host).
                    onPhotos = { urls, caps, start -> reviewPhotos = Triple(urls, caps, start) },
                )
            }
        }
    }
    reviewPhotos?.let { (urls, caps, start) ->
        PhotoGallery(urls, caps, start) { reviewPhotos = null }
    }
}

@Composable
private fun ReviewsTab(
    place: Place,
    reviews: List<Review>,
    loading: Boolean,
    found: Int,
    onRetry: () -> Unit,
    ink: Color,
    dim: Color,
    onPhotoTap: (List<String>, Int, String?) -> Unit = { _, _, _ -> },
    onReadAll: (() -> Unit)? = null,
) {
    // Search within the loaded reviews (author or text, case-insensitive). Resets per place.
    var reviewQuery by remember(place.id) { mutableStateOf("") }
    Column {
        place.rating?.let { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Text(String.format(Locale.US, "%.1f", r), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.width(8.dp))
                RatingStars(r)
                place.reviewCount?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.place_review_count, it), style = MaterialTheme.typography.bodyMedium, color = dim)
                }
            }
        }
        // Entry to the full-screen live Google reviews — all of them, plus Google's own SORT and
        // server-side search. The label says so (the button used to just say "Read all").
        onReadAll?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(place.reviewCount?.let { stringResource(R.string.place_all_n_reviews, it) } ?: stringResource(R.string.place_all_reviews))
            }
        }
        // Featured-review quote is only a TEASER while the real reviews are still streaming in —
        // once they arrive it'd be a redundant "quote break" between the button and the list, so
        // drop it then (the actual reviews below say it better).
        if (reviews.isEmpty()) {
            place.featuredReview?.let { rev ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.FormatQuote, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(rev, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = ink, modifier = Modifier.weight(1f))
                }
            }
        }
        // The WebView scrape legitimately takes a while (~10-40 s on busy places), so show REAL
        // progress the whole time it runs: the scraper streams its running count (the "N of ~M"
        // bar) AND the reviews themselves, which fill the list BELOW this header as they're found
        // — the wait reads as work arriving, not a hang.
        if (loading) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // What the scrape can at most deliver: the place's own count, capped like the scraper.
                val target = (place.reviewCount ?: 0).coerceAtMost(50)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            found > 0 && target > 0 -> stringResource(R.string.place_reviews_progress, found, maxOf(target, found))
                            found > 0 -> stringResource(R.string.place_reviews_so_far, found)
                            else -> stringResource(R.string.place_gathering_reviews)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = dim,
                    )
                }
                if (found > 0 && target > 0) {
                    LinearProgressIndicator(
                        progress = { (found.toFloat() / maxOf(target, found)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
        when {
            // Still loading with nothing streamed yet — the header above is the whole story.
            loading && reviews.isEmpty() -> {}
            // The count says this place HAS reviews but we have none — the RPC flaked (it's
            // intermittent), so this is a load FAILURE, not a review-less place. Say so and
            // let the user retry instead of lying with "No reviews available."
            reviews.isEmpty() && (place.reviewCount ?: 0) > 0 -> Row(
                Modifier.fillMaxWidth().clickable { onRetry() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.place_reviews_load_failed), style = MaterialTheme.typography.bodyMedium, color = dim)
            }
            reviews.isEmpty() -> Text(stringResource(R.string.place_no_reviews), style = MaterialTheme.typography.bodyMedium, color = dim)
            else -> {
                // Reaches here DURING loading too — partials stream in and render under the
                // progress header above, growing until the scrape completes.
                // Search box (only once there's enough to be worth filtering) — matches text OR
                // author. Held back until the scrape COMPLETES: popping a text field in above rows
                // the user is reading mid-stream shifts everything under their finger; appearing at
                // completion it takes the space the progress header just vacated (a near-swap).
                if (!loading && reviews.size >= 5) {
                    OutlinedTextField(
                        value = reviewQuery,
                        onValueChange = { reviewQuery = it },
                        placeholder = { Text(stringResource(R.string.place_search_reviews), color = dim) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (reviewQuery.isNotEmpty()) {
                            {
                                Icon(
                                    Icons.Default.Close, contentDescription = stringResource(R.string.place_clear_review_search), tint = dim,
                                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { reviewQuery = "" },
                                )
                            }
                        } else null,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).dpadFieldEscape(),
                    )
                }
                val q = reviewQuery.trim()
                val shown = if (q.isEmpty()) reviews else reviews.filter {
                    it.text?.contains(q, ignoreCase = true) == true || it.author.contains(q, ignoreCase = true)
                }
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.place_no_reviews_mention, q, reviews.size),
                        style = MaterialTheme.typography.bodyMedium, color = dim,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    shown.forEach { ReviewRow(it, ink, dim, onPhotoTap, q) }
                }
            }
        }
    }
}

/** Emphasise every occurrence of [query] in [text] (case-insensitive) in bold — used to show
 *  what a review search matched. Empty query → plain text. */
private fun emphasize(text: String, query: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    val lc = text.lowercase()
    val q = query.lowercase()
    var i = 0
    while (true) {
        val idx = lc.indexOf(q, i)
        if (idx < 0) { append(text.substring(i)); break }
        append(text.substring(i, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(idx, idx + query.length)) }
        i = idx + query.length
    }
}

@Composable
private fun ReviewRow(review: Review, ink: Color, dim: Color, onPhotoTap: (List<String>, Int, String?) -> Unit = { _, _, _ -> }, query: String = "") {
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
                Text(emphasize(review.author, query), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
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
            Text(emphasize(it, query), style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(top = 6.dp))
        }
        // User-attached review photos (Google-style thumbnail strip) — tap to open the shared
        // full-screen gallery, captioned "Author · date" (the whole review's photo set, opened
        // at the tapped index).
        if (review.photos.isNotEmpty()) {
            val caption = listOfNotNull(review.author.ifBlank { null }, review.relativeTime).joinToString(" · ").ifBlank { null }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                review.photos.forEachIndexed { i, url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // Subtle fill so the slot isn't a transparent gap while the
                        // thumbnail loads (or if it fails).
                        modifier = Modifier.size(104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(dim.copy(alpha = 0.12f))
                            .clickable { onPhotoTap(review.photos, i, caption) },
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
            Text(stringResource(R.string.place_from_the_owner), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = dim, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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

/** One Google-style action pill — a rounded chip with an icon + label, sized to its content so a row
 *  of them scrolls horizontally. [emphasized] = the filled primary treatment (Directions). */
@Composable
private fun ActionPill(icon: ImageVector, label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val bg = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val fg = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Share action: opens a small menu — a Google Maps link, a keyless geo: pin
 *  (opens in any maps app, incl. Vela), raw coordinates, or just the address. */
@Composable
private fun ShareIconButton(place: Place, tint: Color) {
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
                    context.getString(R.string.place_share_place),
                ),
            )
        }
        open = false
    }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.place_share), tint = tint, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_share_gmaps_link)) },
                onClick = { share("${place.name}\nhttps://www.google.com/maps/search/?api=1&query=$lat%2C$lng") },
            )
            // A geo: URI opens in ANY maps app (incl. Vela) — no google.com, the
            // degoogled-friendly way to send a pin.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_share_map_pin)) },
                onClick = { share("${place.name}\ngeo:$lat,$lng?q=$lat,$lng(${Uri.encode(place.name)})") },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_share_coordinates)) },
                onClick = { share("$lat, $lng") },
            )
            place.address?.let { addr ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.place_share_address)) },
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
        Text(stringResource(R.string.place_popular_times), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink)
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
            Text(stringResource(R.string.place_busyness_right_now, busynessLabel(nowOcc)), style = MaterialTheme.typography.bodySmall, color = dim)
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

private data class HolidayHours(val label: String, val whenLabel: String, val hours: String, val daysAway: Int)

private val HOLIDAY_DAY_NAMES = mapOf(
    "monday" to java.time.DayOfWeek.MONDAY, "tuesday" to java.time.DayOfWeek.TUESDAY,
    "wednesday" to java.time.DayOfWeek.WEDNESDAY, "thursday" to java.time.DayOfWeek.THURSDAY,
    "friday" to java.time.DayOfWeek.FRIDAY, "saturday" to java.time.DayOfWeek.SATURDAY,
    "sunday" to java.time.DayOfWeek.SUNDAY,
)

/** From weekly hours whose affected day is tagged " · <holiday label>" (e.g. "Thursday: Closed ·
 *  Independence Day"), return the SOONEST upcoming special-hours day (today first) so the place card
 *  can flag it Google-style instead of leaving it buried on one day's row. Null if none this week. */
private fun upcomingHoliday(hours: List<String>, today: java.time.LocalDate): HolidayHours? {
    val todayDow = today.dayOfWeek
    return hours.mapNotNull { line ->
        val dot = line.lastIndexOf(" · ")
        if (dot < 0) return@mapNotNull null
        val label = line.substring(dot + 3).trim().ifBlank { return@mapNotNull null }
        val head = line.substring(0, dot)
        val colon = head.indexOf(':')
        if (colon < 0) return@mapNotNull null
        val dow = HOLIDAY_DAY_NAMES[head.substring(0, colon).trim().lowercase()] ?: return@mapNotNull null
        val hrs = head.substring(colon + 1).trim().ifBlank { "Hours may differ" }
        val daysAway = ((dow.value - todayDow.value) + 7) % 7 // 0 = today, 1..6 = upcoming this week
        val whenLabel = when (daysAway) {
            0 -> "today"
            1 -> "tomorrow"
            else -> dow.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        }
        HolidayHours(label, whenLabel, hrs, daysAway)
    }.minByOrNull { it.daysAway }
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
                stringResource(R.string.place_hours),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (!expanded) {
                days.firstOrNull()?.let {
                    // Just the hours in the collapsed summary — strip any " · Holiday" suffix (it's
                    // already shown in the amber callout above) so it can't squeeze the "Hours" label.
                    Text(
                        it[1].substringBefore("·").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.place_collapse_hours) else stringResource(R.string.place_expand_hours),
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
