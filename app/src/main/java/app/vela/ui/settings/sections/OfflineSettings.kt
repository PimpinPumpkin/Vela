package app.vela.ui.settings.sections

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.offline.OfflineMaps
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.SubHead
import app.vela.ui.dpadFieldEscape // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import app.vela.ui.rememberDpadFocusKeeper // focus handoff for swap-in controls (docs/dpad.md)
import app.vela.ui.DpadFocusHandoff
import app.vela.ui.dpadFocusKept
import org.maplibre.android.offline.OfflineRegion

/**
 * Offline sub-screen: map-area tile downloads and the routing-region picker. The old page kept this
 * whole section collapsed by default so its long region list didn't bury the sections below; as its
 * own page that concern is gone, so the body renders directly.
 *
 * [onCloseSettings] closes ALL of Settings back to the map (not just this page) - the download
 * buttons use it so the user sees the on-map progress card.
 */
@Composable
internal fun OfflineSettingsScreen(vm: MapViewModel, onBack: () -> Unit, onCloseSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRegion by remember { mutableStateOf<app.vela.offline.RoutingRegion?>(null) }
    confirmRegion?.let { region ->
        val packRegion = state.poiPackRegions.firstOrNull { it.id == region.id }
        app.vela.ui.VelaDialog(
            onDismissRequest = { confirmRegion = null },
            title = stringResource(R.string.settings_region_confirm_title, region.name),
            text = { Text(stringResource(R.string.settings_region_confirm_body, region.name, fmtMb(regionInstalledMb(region, packRegion)))) },
            confirmText = stringResource(R.string.settings_download),
            onConfirm = { confirmRegion = null; vm.downloadRoutingGraph(region) },
            dismissText = stringResource(R.string.settings_cancel),
            onDismiss = { confirmRegion = null },
        )
    }
    SettingsScaffold(stringResource(R.string.settings_offline), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_offline_hint))
        var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
        LaunchedEffect(Unit) { OfflineMaps.list(context) { regions = it } }
        // -1 = not loaded yet; used only to decide the "saved areas predate offline addresses" nudge below.
        var offlineAddrCount by remember { mutableStateOf(-1) }
        LaunchedEffect(Unit) { vm.offlineAddressCount { offlineAddrCount = it } }
        SettingsGroup(title = stringResource(R.string.settings_offline_map_area)) {
        FilledTonalButton(
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            modifier = topRow.padding(start = 16.dp, top = 4.dp).dpadHighlight(androidx.compose.foundation.shape.CircleShape),
            onClick = {
                vm.downloadViewport()
                onCloseSettings() // back to the map so the user sees the download progress
            },
            enabled = vm.hasViewport(),
        ) { Text(stringResource(R.string.settings_offline_download_viewport)) }
        Hint(stringResource(R.string.settings_offline_download_viewport_hint))
        if (regions.isEmpty()) {
            Hint(stringResource(R.string.settings_offline_no_areas))
        } else {
            regions.forEachIndexed { ri, r ->
                if (ri > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        OfflineMaps.nameOf(r),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { OfflineMaps.delete(r) { OfflineMaps.list(context) { regions = it } } }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_offline_delete_area))
                    }
                }
            }
        }
        }
        // Nudge for areas saved before the offline address geocoder existed: they have tiles + POIs but no
        // address data, so offline address search/routing would silently miss. One tap re-fetches the
        // address index for every saved area. Only shown when there ARE areas and the index is still empty.
        if (regions.isNotEmpty() && offlineAddrCount == 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.settings_offline_addresses_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = {
                            vm.refreshOfflineDataForSavedAreas()
                            onCloseSettings() // back to the map to watch the per-area progress
                        },
                        modifier = Modifier
                            .dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape)
                            .padding(top = 8.dp),
                    ) { Text(stringResource(R.string.settings_offline_addresses_update)) }
                }
            }
        }

        SubHead(stringResource(R.string.settings_routing_regions))
        LaunchedEffect(Unit) { vm.refreshRoutingRegions() }
        Hint(stringResource(R.string.settings_routing_regions_hint))
        if (state.routingRegions.isEmpty()) {
            Hint(stringResource(R.string.settings_routing_no_regions))
        } else {
            val loc = state.myLocation
            val covers = { r: app.vela.offline.RoutingRegion ->
                loc != null && loc.lat in r.s..r.n && loc.lng in r.w..r.e
            }
            // The region you're IN = the SMALLEST bbox that contains you. Region boxes carry a Geofabrik
            // buffer that spills across borders (British Columbia's box dips into Sacramento), so "any box that
            // covers you" mislabels big neighbours - the smallest covering box is the specific one. Sort:
            // installed first (manage what you have), then that primary region, then everything by name.
            val primary = state.routingRegions.filter(covers)
                .minByOrNull { (it.n - it.s) * (it.e - it.w) }
            val ordered = state.routingRegions.sortedWith(
                compareByDescending<app.vela.offline.RoutingRegion> { it.id in state.routingInstalledIds }
                    .thenByDescending { it.id == primary?.id }
                    .thenBy { it.name },
            )
            // With a world-sized catalog, a name filter makes a region you're TRAVELLING to findable
            // without scrolling past a hundred others (the sort above handles where you are now).
            var routeFilter by remember { mutableStateOf("") }
            if (state.routingRegions.size > 8) {
                OutlinedTextField(
                    value = routeFilter,
                    onValueChange = { routeFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).dpadFieldEscape(),
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = app.vela.ui.settings.settingsFieldColors(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (routeFilter.isNotEmpty()) {
                            IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { routeFilter = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.settings_clear_filter))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_routing_filter_placeholder, state.routingRegions.size), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                )
            }
            val shown = if (routeFilter.isBlank()) ordered
                else ordered.filter { it.name.contains(routeFilter.trim(), ignoreCase = true) }
            if (shown.isEmpty()) {
                Hint(stringResource(R.string.settings_routing_no_match, routeFilter.trim()))
            }
            SettingsGroup {
            shown.forEachIndexed { regionIdx, region ->
                if (regionIdx > 0) GroupDivider()
                val installed = region.id in state.routingInstalledIds
                val downloading = state.routingDownloadingId == region.id
                val packDownloading = state.poiPackDownloadingId == region.id
                val packInstalled = region.id in state.poiPackInstalledIds
                // A fresher pack is published than the one installed → offer an in-place update
                // (a small row-level delta when the manifest carries one, else a full re-download).
                val packRegion = state.poiPackRegions.firstOrNull { it.id == region.id }
                val updateAvailable = installed && packInstalled && packRegion != null &&
                    packRegion.rev > (state.poiPackInstalledRevs[region.id] ?: 0)
                val here = region.id == primary?.id
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(region.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(
                            when {
                                downloading -> stringResource(R.string.settings_routing_downloading, state.routingDownloadPct)
                                packDownloading -> stringResource(R.string.settings_routing_places_downloading, state.poiPackDownloadPct)
                                updateAvailable -> stringResource(R.string.settings_routing_update_available)
                                installed && packInstalled -> stringResource(R.string.settings_routing_installed_places)
                                installed -> stringResource(R.string.settings_routing_installed)
                                here -> stringResource(R.string.settings_routing_size_installed_here, fmtMb(regionInstalledMb(region, packRegion)))
                                else -> stringResource(R.string.settings_routing_size_installed, fmtMb(regionInstalledMb(region, packRegion)))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if ((here && !installed && !downloading) || updateAvailable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // D-pad: same swap-in control as the voice rows (Download -> spinner ->
                    // Get places/Delete) - the keeper re-places focus on the new variant so
                    // the highlight doesn't teleport to the top of the page (user report).
                    val keeper = rememberDpadFocusKeeper()
                    when {
                        downloading || packDownloading -> {
                            DpadFocusHandoff(keeper)
                            CircularProgressIndicator(
                                Modifier
                                    .size(20.dp)
                                    .dpadFocusKept(keeper)
                                    .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                                    .focusable(),
                                strokeWidth = 2.dp,
                            )
                        }
                        updateAvailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                            DpadFocusHandoff(keeper)
                            FilledTonalButton(
                                onClick = { vm.downloadPoiPackFor(region, update = true) },
                                enabled = state.routingDownloadingId == null && state.poiPackDownloadingId == null,
                                modifier = Modifier.dpadFocusKept(keeper),
                            ) { Text(stringResource(R.string.settings_update_places)) }
                            IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                            }
                        }
                        // Installed before place packs existed (or its pack was skipped): offer just
                        // the pack, so offline search covers the region without a graph re-download.
                        installed && !packInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                            DpadFocusHandoff(keeper)
                            FilledTonalButton(
                                onClick = { vm.downloadPoiPackFor(region) },
                                enabled = state.routingDownloadingId == null && state.poiPackDownloadingId == null,
                                modifier = Modifier.dpadFocusKept(keeper),
                            ) { Text(stringResource(R.string.settings_get_places)) }
                            IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                            }
                        }
                        installed -> {
                            DpadFocusHandoff(keeper)
                            IconButton(onClick = { vm.deleteRoutingGraph(region.id) }, modifier = Modifier.dpadFocusKept(keeper)) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                            }
                        }
                        else -> {
                            DpadFocusHandoff(keeper)
                            FilledTonalButton(
                                // Big regions confirm first with the real installed size (issue #214:
                                // Germany reads 1.6 GB on the row but lands at ~8 GB on disk).
                                onClick = {
                                    if (regionInstalledMb(region, packRegion) > CONFIRM_MB) confirmRegion = region
                                    else vm.downloadRoutingGraph(region)
                                },
                                enabled = state.routingDownloadingId == null,
                                modifier = Modifier.dpadFocusKept(keeper),
                            ) { Text(stringResource(R.string.settings_download)) }
                        }
                    }
                    LaunchedEffect(downloading, packDownloading, updateAvailable, installed, packInstalled) { keeper.retarget() }
                }
            }
            }
        }
        // What offline data actually costs on this phone (issue #214: 8 GB arrived unannounced,
        // and 533 MB of "empty" app is mostly the browsing cache with no way to clear it).
        SubHead(stringResource(R.string.settings_storage_title))
        var storageTick by remember { mutableStateOf(0) }
        val storage by produceState<MapViewModel.OfflineStorage?>(null, storageTick, state.routingInstalledIds, state.poiPackInstalledIds) {
            value = vm.offlineStorageBreakdown()
        }
        val storageScope = rememberCoroutineScope()
        SettingsGroup {
            storage?.let { st ->
                StorageRow(stringResource(R.string.settings_storage_maps), st.mapsMb)
                GroupDivider()
                StorageRow(stringResource(R.string.settings_storage_routing), st.routingMb)
                GroupDivider()
                StorageRow(stringResource(R.string.settings_storage_places), st.placesMb)
                GroupDivider()
                StorageRow(stringResource(R.string.settings_storage_voices), st.voicesMb)
            } ?: Hint(stringResource(R.string.settings_storage_measuring))
            androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { storageScope.launch { vm.clearMapCache(); storageTick++ } },
                    modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                ) { Text(stringResource(R.string.settings_clear_map_cache)) }
            }
            Hint(stringResource(R.string.settings_clear_map_cache_hint))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One "label ..... size" line in the storage group. */
@Composable
private fun StorageRow(label: String, mb: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(fmtMb(mb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The size a region really lands at: manifest installedMb when the bake published it, else an
 *  estimate from the zip (graphs unpack ~1.8x, packs ~2.35x - the WA pack measured 143 -> 335 MB).
 *  The graph and its place pack install together, so the shown number is their SUM. */
internal fun regionInstalledMb(graph: app.vela.offline.RoutingRegion, pack: app.vela.offline.RoutingRegion?): Int {
    val g = if (graph.installedMb > 0) graph.installedMb else (graph.sizeMb * 1.8).toInt()
    val p = pack?.let { if (it.installedMb > 0) it.installedMb else (it.sizeMb * 2.35).toInt() } ?: 0
    return g + p
}

internal fun fmtMb(mb: Int): String =
    if (mb >= 1024) String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024f) else "$mb MB"

private const val CONFIRM_MB = 1024
