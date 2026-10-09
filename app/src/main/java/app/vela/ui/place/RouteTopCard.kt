package app.vela.ui.place

import app.vela.ui.icons.Sym

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.vela.ui.item
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadFieldEscape

// The map result pins' red (PoiIcons.RESULT_RED) — the destination pin on this card is the same
// species as the pin the route ends at on the map, so the two must stay the same ink.
private val DestinationRed = Color(0xFFDB4437)

// One endpoint row's height; the connector dots between rows key off it too.
private val ENDPOINT_ROW = 44.dp
private val GLYPH_RAIL = 26.dp

/**
 * Google's directions header: while the route chooser is open the search bar swaps for this card —
 * origin row, stops, destination row down a glyph rail (origin ring, connector dots, red pin),
 * back arrow on the left, swap on the right. The rows moved OUT of the bottom chooser (which keeps
 * mode chips / leave-now / routes / Start), so the endpoints stay visible and editable even while
 * the chooser is collapsed to its Start bar — and the whole thing reads like gmaps on a small
 * screen. Every control is a D-pad focus stop with a ring (docs/dpad.md).
 */
@Composable
fun RouteTopCard(
    originName: String,
    originIsMe: Boolean,
    destinationName: String,
    stops: List<String> = emptyList(),
    showStopControls: Boolean = true, // false on transit: no waypoints there
    onEditOrigin: (() -> Unit)? = null,
    onEditDestination: (() -> Unit)? = null,
    onEditStops: () -> Unit = {},
    onAddStop: (() -> Unit)? = null,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    // The Google-style chooser experiment: no visible Add stop row; a menu on the top right holds
    // Edit stops and Add stop instead, with the swap under it, as Google lays the card out.
    googleStyle: Boolean = false,
    // Saving the picked route (issue #622) under a name; null hides the item (no route yet, transit).
    onSaveRoute: ((String, Boolean) -> Unit)? = null,
    defaultRouteName: String = "",
    // The saved route the chooser was opened from: "Save changes to <name>" overwrites it.
    editingRouteName: String? = null,
    onUpdateRoute: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // A trip from a link: while its places are looked up the card lists them as the link names
    // them, and nothing on it can be edited until they have all answered.
    val link = LinkTrip.view.value
    val resolving = link?.resolving == true
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // Same tone as the search bar it replaces, so the top chrome reads as one family.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                Icon(
                    Sym.ArrowBack,
                    contentDescription = stringResource(R.string.place_close_directions),
                    tint = dim,
                )
            }
            if (resolving && link != null) Column(Modifier.weight(1f)) {
                LinkTripRows(link, meName = originName, ink = ink, dim = dim)
            } else Column(Modifier.weight(1f)) {
                EndpointRow(
                    text = originName,
                    // Blue only for "Your location", as on Google's card; a named place reads in plain ink.
                    textColor = if (originIsMe) MaterialTheme.colorScheme.primary else ink,
                    editable = onEditOrigin != null,
                    editLabel = stringResource(R.string.place_change_start),
                    onClick = onEditOrigin,
                ) {
                    // Origin = a ring, teal when it's literally you (the app's "this is me" ink;
                    // gmaps uses its location blue the same way).
                    Box(
                        Modifier
                            .size(12.dp)
                            .border(2.dp, if (originIsMe) MaterialTheme.colorScheme.primary else dim, CircleShape),
                    )
                }
                ConnectorRow(dim)
                if (stops.isNotEmpty() && showStopControls) {
                    // Google's double-dot handle on stop rows (issue #405): the card's own
                    // controls were a swap and a plus, so nothing said stops can be reordered.
                    // The handle is the same glyph the stops editor drags by; tapping the row
                    // opens that editor.
                    EndpointRow(
                        text = stops.first(),
                        textColor = ink,
                        editable = true,
                        editLabel = stringResource(R.string.stops_edit),
                        onClick = onEditStops,
                        trailing = { Icon(Sym.DragHandle, contentDescription = null, tint = dim, modifier = Modifier.size(20.dp).padding(end = 2.dp)) },
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dim))
                    }
                    // Extra stops read as their own quiet line under the first (the old inline
                    // "+N" was easy to miss, user 2026-07-14) - a second door into the stops editor
                    // (user 2026-07-14). Its pencil went the same way as the endpoint rows' (issue
                    // #255); the row carries the label instead.
                    if (stops.size > 1) {
                        val editStopsLabel = stringResource(R.string.stops_edit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .dpadHighlight(RoundedCornerShape(8.dp))
                                .semantics { contentDescription = editStopsLabel }
                                .clickable { onEditStops() },
                        ) {
                            Spacer(Modifier.width(GLYPH_RAIL + 8.dp))
                            Text(
                                pluralStringResource(R.plurals.topcard_more_stops, stops.size - 1, stops.size - 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = dim,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(Sym.DragHandle, contentDescription = null, tint = dim, modifier = Modifier.size(20.dp).padding(end = 2.dp))
                        }
                    }
                    ConnectorRow(dim)
                }
                EndpointRow(
                    text = destinationName,
                    textColor = ink,
                    bold = true,
                    editable = onEditDestination != null,
                    editLabel = stringResource(R.string.place_change_destination),
                    onClick = onEditDestination,
                ) {
                    Icon(Sym.Place, contentDescription = null, tint = DestinationRed, modifier = Modifier.size(20.dp))
                }
                // Add stop keeps its own quiet row (gmaps buries it in an overflow menu; a
                // visible row is the discoverable version and the card has the room).
                if (!googleStyle && showStopControls && onAddStop != null && stops.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .dpadHighlight(RoundedCornerShape(8.dp))
                            .clickable { onAddStop() }
                            .padding(vertical = 4.dp),
                    ) {
                        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) {
                            Icon(Sym.Add, contentDescription = null, tint = dim, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.place_add_stop), style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                }
                // A place from the link that found nothing stays named here until the trip is edited.
                link?.notFound?.takeIf { it.isNotEmpty() }?.let { NotFoundNote(it) }
            }
            if (!resolving) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if ((googleStyle && showStopControls) || onSaveRoute != null) {
                    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var naming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var pinning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    if (pinning) TripShortcutDialog(destinationName, travelModeKey = app.vela.ui.RouteActions.modeKey?.invoke() ?: "drive", onDismiss = { pinning = false })
                    if (naming && onSaveRoute != null) {
                        var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(defaultRouteName) }
                        var keepStops by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                        app.vela.ui.VelaDialog(
                            onDismissRequest = { naming = false },
                            title = stringResource(R.string.route_save_title),
                            confirmText = stringResource(R.string.list_save),
                            onConfirm = { naming = false; onSaveRoute(draft, keepStops && stops.isNotEmpty()) },
                            dismissText = stringResource(R.string.list_cancel),
                            onDismiss = { naming = false },
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it.take(60) },
                                singleLine = true,
                                label = { Text(stringResource(R.string.route_save_hint)) },
                                modifier = Modifier.fillMaxWidth().dpadHighlight().dpadFieldEscape(),
                            )
                            // A trip with stops: are they places you stop at (a run), or only the
                            // points that bent the route (a shape)?
                            if (stops.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                ) {
                                    Text(stringResource(R.string.route_save_keep_stops), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Switch(checked = keepStops, onCheckedChange = { keepStops = it }, modifier = Modifier.dpadHighlight(CircleShape))
                                }
                                if (!keepStops) Text(stringResource(R.string.route_save_keep_stops_hint), style = MaterialTheme.typography.bodySmall, color = dim)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                            Icon(
                                Sym.MoreVert,
                                contentDescription = stringResource(R.string.exp_chooser_more),
                                tint = dim,
                            )
                        }
                        app.vela.ui.VelaMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (googleStyle && showStopControls) {
                                item(stringResource(R.string.stops_edit)) { menu = false; onEditStops() }
                                if (onAddStop != null) item(stringResource(R.string.place_add_stop)) { menu = false; onAddStop() }
                            }
                            if (editingRouteName != null && onUpdateRoute != null) {
                                item(stringResource(R.string.route_update, editingRouteName)) { menu = false; onUpdateRoute(stops.isNotEmpty()) }
                            }
                            if (onSaveRoute != null) item(stringResource(R.string.route_save)) { menu = false; naming = true }
                            item(stringResource(R.string.trip_shortcut_add)) { menu = false; pinning = true }
                        }
                    }
                }
                IconButton(onClick = onSwap, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                    Icon(
                        Sym.SwapVert,
                        contentDescription = stringResource(R.string.place_swap_start_destination),
                        tint = dim,
                    )
                }
                // With stops in play the labeled Add-stop row is gone (the stops summary row took
                // its slot), so adding ANOTHER stop gets this compact + under the swap.
                if (!googleStyle && showStopControls && onAddStop != null && stops.isNotEmpty()) {
                    IconButton(onClick = onAddStop, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                        Icon(
                            Sym.Add,
                            contentDescription = stringResource(R.string.place_add_stop),
                            tint = dim,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One endpoint line: fixed glyph rail + the name, gmaps' row grammar.
 *
 * [editable] no longer draws a pencil (issue #255): the whole row is the control, the glyph rail
 * already says what each line is, and a pencil per line was three of them stacked on one small card
 * saying nothing the tap target did not. It still decides the row's accessibility name, since that
 * is the one thing the icon was carrying.
 */
@Composable
private fun EndpointRow(
    text: String,
    textColor: Color,
    editable: Boolean,
    editLabel: String,
    onClick: (() -> Unit)?,
    bold: Boolean = false,
    // Drawn after the text at the row's end (the stops' drag handle).
    trailing: (@Composable () -> Unit)? = null,
    glyph: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ENDPOINT_ROW)
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .dpadHighlight(RoundedCornerShape(10.dp))
                        // The pencil carried the row's accessibility name; with it gone the row
                        // has to say what tapping it does, or a screen reader just reads a place
                        // name with no hint that it is an edit control.
                        .semantics { contentDescription = editLabel }
                        .clickable { onClick() }
                } else Modifier,
            ),
    ) {
        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) { Spacer(Modifier.weight(1f)); trailing() }
    }
}

/** How many of a link's stops the loading card lists before the rest are counted. */
private const val LINK_ROWS_MAX = 6

/**
 * The card while a link's places are looked up: the start (or you), every stop and the end, each
 * by the name or address the link carries, with a mark for where its lookup stands. Nothing here
 * is a control; the back arrow beside it still cancels.
 */
@Composable
private fun LinkTripRows(link: LinkTrip.View, meName: String, ink: Color, dim: Color) {
    val me = link.origin == null
    EndpointRow(
        text = link.origin?.label ?: meName,
        textColor = if (me) MaterialTheme.colorScheme.primary else ink,
        editable = false, editLabel = "", onClick = null,
        trailing = link.origin?.lookup?.let { l -> @Composable { LookupMark(l) } },
    ) {
        Box(Modifier.size(12.dp).border(2.dp, if (me) MaterialTheme.colorScheme.primary else dim, CircleShape))
    }
    ConnectorRow(dim)
    for (s in link.stops.take(LINK_ROWS_MAX)) {
        EndpointRow(
            text = s.label, textColor = ink, editable = false, editLabel = "", onClick = null,
            trailing = { LookupMark(s.lookup) },
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dim))
        }
        ConnectorRow(dim)
    }
    if (link.stops.size > LINK_ROWS_MAX) {
        val more = link.stops.size - LINK_ROWS_MAX
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(GLYPH_RAIL + 8.dp))
            Text(pluralStringResource(R.plurals.topcard_more_stops, more, more), style = MaterialTheme.typography.labelMedium, color = dim)
        }
        ConnectorRow(dim)
    }
    EndpointRow(
        text = link.destination.label, textColor = ink, bold = true,
        editable = false, editLabel = "", onClick = null,
        trailing = { LookupMark(link.destination.lookup) },
    ) {
        Icon(Sym.Place, contentDescription = null, tint = DestinationRed, modifier = Modifier.size(20.dp))
    }
    Text(
        stringResource(R.string.link_trip_finding),
        style = MaterialTheme.typography.labelMedium,
        color = dim,
        modifier = Modifier
            .padding(start = GLYPH_RAIL + 8.dp, top = 2.dp, bottom = 4.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    )
}

/** Where one place's lookup stands: a spinner, a check, or a warning when it found nothing. */
@Composable
private fun LookupMark(lookup: LinkTrip.Lookup) {
    val m = Modifier.padding(end = 6.dp).size(18.dp)
    when (lookup) {
        LinkTrip.Lookup.LOOKING -> {
            val cd = stringResource(R.string.link_trip_looking_cd)
            androidx.compose.material3.CircularProgressIndicator(
                m.padding(1.dp).semantics { contentDescription = cd }, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            )
        }
        LinkTrip.Lookup.FOUND -> Icon(Sym.Check, contentDescription = stringResource(R.string.link_trip_found_cd), tint = MaterialTheme.colorScheme.primary, modifier = m)
        LinkTrip.Lookup.NOT_FOUND -> Icon(Sym.Warning, contentDescription = stringResource(R.string.link_trip_not_found_cd), tint = MaterialTheme.colorScheme.error, modifier = m)
    }
}

/** The places from a link that found nothing, under the trip, read out when it appears. */
@Composable
private fun NotFoundNote(names: List<String>) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(top = 4.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    ) {
        Box(Modifier.width(GLYPH_RAIL).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            Icon(Sym.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.link_trip_not_found, names.size, names.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The dots between glyphs plus a hairline under the text side — gmaps' rail connector. */
@Composable
private fun ConnectorRow(dim: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(2) { Box(Modifier.size(2.5.dp).clip(CircleShape).background(dim.copy(alpha = 0.7f))) }
            }
        }
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = dim.copy(alpha = 0.18f))
    }
}

private val SHORTCUT_GLYPHS: Map<String, androidx.compose.ui.graphics.vector.ImageVector> = mapOf(
    "drive" to Sym.DirectionsCar, "transit" to Sym.DirectionsBus, "bike" to Sym.DirectionsBike, "walk" to Sym.DirectionsWalk,
    "home" to Sym.Home, "work" to Sym.Work, "star" to Sym.Star, "favorite" to Sym.Favorite, "flag" to Sym.Flag,
    "place" to Sym.Place, "restaurant" to Sym.Restaurant, "shopping" to Sym.ShoppingCart,
)

/** Name, glyph and look for a trip's home-screen shortcut (issue #675), then the launcher's own
 *  confirmation. The look can copy the launcher's themed icons, which a shortcut does not get
 *  by itself. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripShortcutDialog(destinationName: String, travelModeKey: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(destinationName.take(24)) }
    var icon by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(travelModeKey) }
    var themed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("trip_shortcut_themed", false)) }
    app.vela.ui.VelaDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.trip_shortcut_add),
        confirmText = stringResource(R.string.trip_shortcut_add),
        onConfirm = {
            prefs.edit().putBoolean("trip_shortcut_themed", themed).apply()
            onDismiss()
            app.vela.ui.RouteActions.pinTrip?.invoke(name, icon, themed)
        },
        dismissText = stringResource(R.string.list_cancel),
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().dpadHighlight().dpadFieldEscape(),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            app.vela.ui.map.PoiIcons.SHORTCUT_ICON_KEYS.forEach { key ->
                val sel = key == icon
                androidx.compose.material3.Surface(
                    shape = CircleShape,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp).dpadHighlight(CircleShape).clickable { icon = key },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            SHORTCUT_GLYPHS[key] ?: Sym.DirectionsCar, contentDescription = key,
                            tint = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.trip_shortcut_themed), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = themed, onCheckedChange = { themed = it }, modifier = Modifier.dpadHighlight(CircleShape))
        }
    }
}
