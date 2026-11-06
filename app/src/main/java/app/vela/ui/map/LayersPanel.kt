package app.vela.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.Buildings3d
import app.vela.ui.GoogleFree
import app.vela.ui.MapLook
import app.vela.ui.MapPoiPrefs
import app.vela.ui.SatelliteLayer
import app.vela.ui.Topography
import app.vela.ui.Traffic
import app.vela.ui.TransitLayer
import app.vela.ui.VelaMenu
import app.vela.ui.dpadClickable
import app.vela.ui.dpadHighlight
import app.vela.ui.icons.Sym
import app.vela.ui.icons.SymOutlined
import app.vela.ui.rememberDpadAutoFocus
import app.vela.ui.theme.isMapAmoled
import app.vela.ui.theme.isMapDark

/**
 * The browse map's top-right layers button and the panel it opens (issue #782): picture tiles in
 * three groups, Map type (the five looks and satellite, one chosen), Map details (traffic, transit
 * lines, terrain, 3D buildings, each on or off) and Place icons (Vela data, Google or both), then
 * the Google-free switch. The tiles flip the same holders Settings does, so the two stay in sync.
 * One call from MapScreen, which has no room for more (see the size notes there) and keeps only
 * the gate that decides when the button shows.
 */
@Composable
internal fun BoxScope.LayersCorner(vm: MapViewModel, landscapeChrome: Boolean, darkTheme: Boolean) {
    var open by remember { mutableStateOf(false) }
    // The button takes the accent while something beyond the default map is on. 3D buildings are
    // on by default, so they do not count.
    val anyLayerOn = SatelliteLayer.on.value || Traffic.on.value || TransitLayer.on.value || Topography.on.value || app.vela.ui.WazePolice.on.value
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            // One-line landscape chrome: the chips sit beside the bar, so the button rises a
            // whole row (matches the compass margin in VelaMapView). 23 dp from the end puts
            // this 42 dp button's center on the line the other map buttons share (#778).
            .padding(top = if (landscapeChrome) 74.dp else 128.dp, end = 23.dp),
    ) {
        Surface(
            color = mapButtonColor(darkTheme),
            shape = CircleShape,
            border = mapButtonRing(darkTheme),
            shadowElevation = 3.dp,
            modifier = Modifier.size(42.dp).dpadHighlight(CircleShape),
        ) {
            IconButton(onClick = { open = true }) {
                Icon(
                    SymOutlined.Layers,
                    contentDescription = stringResource(R.string.map_layers),
                    tint = if (anyLayerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
        // VelaMenu, not a bare DropdownMenu (docs/dpad.md): a Popup cannot be pre-focused, so a
        // key-first device gets the chooser dialog, and the first tile takes focus on open.
        VelaMenu(expanded = open, onDismissRequest = { open = false }) {
            LayersPanel(vm, onClose = { open = false })
        }
    }
}

/** Tiles per row. Four keeps the panel inside a 4a-sized portrait screen under the button. */
private const val COLUMNS = 4
private val TILE_W = 68.dp
private val PICTURE_H = 48.dp
private val TILE_GAP = 6.dp

/** One tile: its label, whether it is the chosen or on one (ringed), what a press does, the
 *  requester for the tile that takes focus when the panel opens, and its picture. */
private class Tile(
    val label: String,
    val on: Boolean,
    val onClick: () -> Unit,
    val focus: FocusRequester? = null,
    val picture: @Composable (on: Boolean) -> Unit,
)

@Composable
private fun LayersPanel(vm: MapViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val firstTile = rememberDpadAutoFocus()
    val satellite = SatelliteLayer.on.value
    val look = MapLook.style.value
    val placesSource = MapPoiPrefs.placesSource.value
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Map type is one choice: a look, or satellite over it. Picking a look while satellite is
        // on turns satellite off, which is what choosing a map type means; the satellite tile
        // itself toggles, so a second press returns to the look.
        GroupTitle(stringResource(R.string.map_layers_type))
        TileRows(
            MapStyle.entries.mapIndexed { i, s ->
                Tile(
                    label = stringResource(lookLabel(s)),
                    on = !satellite && look == s,
                    focus = firstTile.takeIf { i == 0 },
                    onClick = {
                        if (look != s) MapLook.set(ctx, s)
                        if (satellite) {
                            SatelliteLayer.set(ctx, false)
                            vm.onSatelliteToggled()
                        }
                    },
                ) { LookSwatch(s) }
            } + Tile(
                label = stringResource(R.string.map_layers_satellite),
                on = satellite,
                onClick = {
                    SatelliteLayer.set(ctx, !satellite)
                    vm.onSatelliteToggled()
                },
            ) { SatelliteSwatch() },
        )
        GroupTitle(stringResource(R.string.map_layers_details))
        TileRows(
            listOf(
                Tile(stringResource(R.string.map_layers_traffic), Traffic.on.value, { Traffic.set(ctx, !Traffic.on.value) }) {
                    GlyphDisc(Sym.Traffic, it)
                },
                Tile(stringResource(R.string.map_layers_transit), TransitLayer.on.value, { TransitLayer.set(ctx, !TransitLayer.on.value) }) {
                    GlyphDisc(Sym.Train, it)
                },
                Tile(stringResource(R.string.map_layers_terrain), Topography.on.value, { Topography.set(ctx, !Topography.on.value) }) {
                    GlyphDisc(Sym.Terrain, it)
                },
                Tile(stringResource(R.string.settings_buildings_3d), Buildings3d.on.value, { Buildings3d.set(ctx, !Buildings3d.on.value) }) {
                    GlyphDisc(Sym.Apartment, it)
                },
                Tile(stringResource(R.string.map_waze_alerts_toggle), app.vela.ui.WazeAlertsPrefs.masterOn.value, { app.vela.ui.WazeAlertsPrefs.setMaster(ctx, !app.vela.ui.WazeAlertsPrefs.masterOn.value) }) {
                    GlyphDisc(Sym.Shield, it)
                },
            ),
        )
        // Where the map's places come from, and the Google-free switch, one tap from the map
        // (issue #626): the same holders Settings > Places and Privacy flip. The places choice only
        // changes the map's pins; search still asks Google unless Google-free is on, which is why
        // that switch sits here too.
        GroupTitle(stringResource(R.string.map_layers_places_title))
        TileRows(
            listOf(
                Triple(MapPoiPrefs.SOURCE_OPEN, R.string.settings_places_source_open, Sym.Storefront),
                Triple(MapPoiPrefs.SOURCE_GOOGLE, R.string.settings_places_source_google, Sym.Language),
                Triple(MapPoiPrefs.SOURCE_BOTH, R.string.settings_places_source_both, Sym.Merge),
            ).map { (id, label, glyph) ->
                Tile(stringResource(label), placesSource == id, { MapPoiPrefs.setPlacesSource(ctx, id) }) { GlyphDisc(glyph, it) }
            },
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(stringResource(R.string.settings_google_free), GoogleFree.on.value) { GoogleFree.set(ctx, it) }
        // Driving mode (issue #727): the map alone in the drive's layout, from the map's own
        // panel. Its bar's X comes back to this button.
        ActionRow(stringResource(R.string.driving_mode), Sym.DirectionsCar) { onClose(); vm.enterDrivingMode() }
    }
}

/** A row that does one thing and closes the panel: the glyph, the label, nothing else. */
@Composable
private fun ActionRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadHighlight(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .dpadClickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun lookLabel(look: MapStyle): Int = when (look) {
    MapStyle.LIBERTY -> R.string.settings_map_look_liberty
    MapStyle.BRIGHT -> R.string.settings_map_look_bright
    MapStyle.POSITRON -> R.string.settings_map_look_positron
    MapStyle.FIORD -> R.string.settings_map_look_fiord
    MapStyle.DARK -> R.string.settings_map_look_dark
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** The tiles in rows of [COLUMNS], left to right then down, which is also the arrow-key order. */
@Composable
private fun TileRows(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tiles.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), verticalAlignment = Alignment.Top) {
                row.forEach { LayerTile(it) }
            }
        }
    }
}

/** A picture with its label under it. The chosen or on tile wears a primary-color ring on the
 *  picture and a primary label; the focus ring (orange, `dpadHighlight`) goes around the whole
 *  tile, so the two never read as one. OK on a focused tile is the tap. */
@Composable
private fun LayerTile(tile: Tile) {
    val shape = RoundedCornerShape(14.dp)
    val pictureShape = RoundedCornerShape(12.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(TILE_W)
            .then(if (tile.focus != null) Modifier.focusRequester(tile.focus) else Modifier)
            .dpadHighlight(shape)
            .clip(shape)
            .dpadClickable(onClick = tile.onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PICTURE_H)
                .clip(pictureShape)
                .border(if (tile.on) 2.5.dp else 1.dp, if (tile.on) scheme.primary else scheme.outlineVariant, pictureShape),
        ) { tile.picture(tile.on) }
        Spacer(Modifier.height(4.dp))
        Text(
            tile.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (tile.on) scheme.primary else scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The one row that stays a switch: a privacy mode, not a thing drawn on the map. The row is the
 *  single focus stop and tap target; the switch only shows the state. */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadHighlight(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .dpadClickable { onToggle(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A detail tile's picture: the glyph the app uses for that thing on a disc, the disc filled with
 *  the primary color while the detail is on. */
@Composable
private fun GlyphDisc(icon: ImageVector, on: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(if (on) scheme.primaryContainer else scheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).background(if (on) scheme.primary else scheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (on) scheme.onPrimary else scheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
    }
}

/** The colors a look's swatch is drawn from: its land, water, park, buildings, and a minor and a
 *  major road, each with the casing the look draws under it (none where the look has none). */
private class LookPalette(
    val land: Color,
    val water: Color,
    val park: Color,
    val building: Color,
    val minor: Color,
    val minorCasing: Color? = null,
    val major: Color,
    val majorCasing: Color? = null,
)

// The Vela look is the palette `applyLight`, `applyDark` and `applyAmoled` paint over Liberty
// (VelaMapView); the other four are the colors in OpenFreeMap's style files as served.
private val VELA_LIGHT = LookPalette(
    land = Color(0xFFF8F7F7), water = Color(0xFF90DAEE), park = Color(0xFFD3F8E1), building = Color(0xFFE8E9ED),
    minor = Color(0xFFAAB9C9), major = Color(0xFF8AA4C0),
)
private val VELA_DARK = LookPalette(
    land = Color(0xFF162640), water = Color(0xFF000D2A), park = Color(0xFF0D3847), building = Color(0xFF1C3B69),
    minor = Color(0xFF3D5A77), major = Color(0xFF476789),
)
private val VELA_AMOLED = LookPalette(
    land = Color(0xFF000000), water = Color(0xFF04080C), park = Color(0xFF050E0A), building = Color(0xFF111418),
    minor = Color(0xFF1A1D22), major = Color(0xFF22262C),
)
private val BRIGHT = LookPalette(
    land = Color(0xFFF8F4F0), water = Color(0xFFAECFE2), park = Color(0xFFD8E8C8), building = Color(0xFFF2EAE2),
    minor = Color(0xFFFFFFFF), minorCasing = Color(0xFFCFCDCA), major = Color(0xFFFFEEAA), majorCasing = Color(0xFFE9AC77),
)
private val POSITRON = LookPalette(
    land = Color(0xFFF2F3F0), water = Color(0xFFC2C8CA), park = Color(0xFFE6E9E5), building = Color(0xFFEAEAE5),
    minor = Color(0xFFE0E0E0), major = Color(0xFFFFFFFF), majorCasing = Color(0xFFD5D5D5),
)
private val FIORD = LookPalette(
    land = Color(0xFF45516E), water = Color(0xFF38435C), park = Color(0xFF4A5C68), building = Color(0xFF2A3450),
    minor = Color(0xFF5A678C), major = Color(0xFF3C4357), majorCasing = Color(0xFF5A678C),
)
private val DARK = LookPalette(
    land = Color(0xFF0C0C0C), water = Color(0xFF1B1B1D), park = Color(0xFF202020), building = Color(0xFF0A0A0A),
    minor = Color(0xFF2A2A2A), major = Color(0xFF121212), majorCasing = Color(0xFF3C3C3C),
)

/** A look's swatch: its land with a water patch, a park patch, two buildings and three roads in
 *  that look's own colors, drawn here rather than loaded. The Vela look follows the map's theme. */
@Composable
private fun LookSwatch(look: MapStyle) {
    val palette = when (look) {
        MapStyle.LIBERTY -> if (isMapAmoled()) VELA_AMOLED else if (isMapDark()) VELA_DARK else VELA_LIGHT
        MapStyle.BRIGHT -> BRIGHT
        MapStyle.POSITRON -> POSITRON
        MapStyle.FIORD -> FIORD
        MapStyle.DARK -> DARK
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(palette.land)
        drawOval(palette.water, topLeft = Offset(0.62f * w, -0.38f * h), size = Size(0.7f * w, 0.84f * h))
        drawRoundRect(palette.park, topLeft = Offset(0.07f * w, 0.56f * h), size = Size(0.36f * w, 0.36f * h), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRect(palette.building, topLeft = Offset(0.1f * w, 0.12f * h), size = Size(0.13f * w, 0.17f * h))
        drawRect(palette.building, topLeft = Offset(0.28f * w, 0.1f * h), size = Size(0.1f * w, 0.2f * h))
        road(Offset(0f, 0.44f * h), Offset(w, 0.44f * h), 3.dp.toPx(), palette.major, palette.majorCasing)
        road(Offset(0.52f * w, 0f), Offset(0.52f * w, h), 2.dp.toPx(), palette.minor, palette.minorCasing)
        road(Offset(0.52f * w, 0.78f * h), Offset(w, 0.78f * h), 2.dp.toPx(), palette.minor, palette.minorCasing)
    }
}

/** A road line as the looks draw one: its casing first, a little wider, then the fill. */
private fun DrawScope.road(from: Offset, to: Offset, width: Float, fill: Color, casing: Color?) {
    if (casing != null) drawLine(casing, from, to, strokeWidth = width + 2.dp.toPx(), cap = StrokeCap.Butt)
    drawLine(fill, from, to, strokeWidth = width, cap = StrokeCap.Butt)
}

/** Imagery from above: dark green ground, a darker wood, a gray built-up block and pale roads. */
@Composable
private fun SatelliteSwatch() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF3B5230))
        drawOval(Color(0xFF2B3F23), topLeft = Offset(-0.2f * w, -0.3f * h), size = Size(0.75f * w, 0.8f * h))
        drawRoundRect(Color(0xFF7D8078), topLeft = Offset(0.5f * w, 0.5f * h), size = Size(0.6f * w, 0.6f * h), cornerRadius = CornerRadius(2.dp.toPx()))
        drawRect(Color(0xFF5E6159), topLeft = Offset(0.56f * w, 0.58f * h), size = Size(0.14f * w, 0.14f * h))
        drawRect(Color(0xFF5E6159), topLeft = Offset(0.76f * w, 0.6f * h), size = Size(0.16f * w, 0.12f * h))
        drawRect(Color(0xFF5E6159), topLeft = Offset(0.6f * w, 0.8f * h), size = Size(0.2f * w, 0.12f * h))
        drawLine(Color(0xFFB5B3A8), Offset(0f, 0.42f * h), Offset(w, 0.42f * h), strokeWidth = 2.dp.toPx())
        drawLine(Color(0xFFB5B3A8), Offset(0.45f * w, 0f), Offset(0.45f * w, h), strokeWidth = 1.5f.dp.toPx())
    }
}
