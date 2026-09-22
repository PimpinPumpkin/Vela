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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadFieldEscape // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadClickable
import androidx.compose.material.icons.filled.ChevronRight
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun OfflineSettingsScreen(vm: MapViewModel, onBack: () -> Unit, onCloseSettings: () -> Unit, onOpenVoice: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRegion by remember { mutableStateOf<app.vela.offline.RoutingRegion?>(null) }
    confirmRegion?.let { region ->
        val packRegion = state.poiPackRegions.firstOrNull { it.id == region.id }
        app.vela.ui.VelaDialog(
            onDismissRequest = { confirmRegion = null },
            title = stringResource(R.string.settings_region_confirm_title, region.name),
            text = { Text(stringResource(R.string.settings_region_confirm_body, region.name, fmtMb(regionInstalledMb(region, packRegion, state.regionExtrasMb[region.id] ?: 0)))) },
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
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_offline_places_with_downloads),
            checked = app.vela.ui.MapPoiPrefs.placesWithDownloads.value,
            onCheckedChange = { app.vela.ui.MapPoiPrefs.setPlacesWithDownloads(context, it) },
            hint = stringResource(R.string.settings_offline_places_with_downloads_hint),
        )
        GroupDivider()
        // What a rebaked region is allowed to do on its own. Deltas make an update a few megabytes
        // instead of a few hundred, but they are still the user's bytes, so the default patches on
        // Wi-Fi and asks nowhere else. A full re-download is never automatic on any of these.
        Text(
            stringResource(R.string.settings_region_updates),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.RegionUpdates.Mode.OFF to R.string.settings_region_updates_off,
            app.vela.ui.RegionUpdates.Mode.WIFI to R.string.settings_region_updates_wifi,
            app.vela.ui.RegionUpdates.Mode.MOBILE to R.string.settings_region_updates_mobile,
        ).forEach { (m, label) ->
            SelectableRow(
                label = stringResource(label),
                selected = app.vela.ui.RegionUpdates.mode.value == m,
                onClick = { app.vela.ui.RegionUpdates.set(context, m) },
            )
        }
        app.vela.ui.RegionUpdates.lastResult.value?.let { Hint(it) }
        }
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

        // What offline data actually costs on this phone, ABOVE the catalog since 2026-09-16 (issue
        // #518: see what you have and clear the cache before scrolling a world of downloads). (Issue #214: 8 GB arrived unannounced,
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
                // Tappable: the voices and the speech models are managed on the Voice and Search
                // pages, so the row takes you to the bigger of the two (Voice).
                StorageRow(stringResource(R.string.settings_storage_voices), st.voicesMb, onClick = onOpenVoice)
            } ?: Hint(stringResource(R.string.settings_storage_measuring))
            androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { storageScope.launch { vm.clearMapCache(); storageTick++ } },
                    modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                ) { Text(stringResource(R.string.settings_clear_map_cache)) }
            }
            Hint(stringResource(R.string.settings_clear_map_cache_hint))
            GroupDivider()
            // The one button that reaches everything, including what no region row can (issue #601).
            var confirmDeleteAll by remember { mutableStateOf(false) }
            if (confirmDeleteAll) {
                app.vela.ui.VelaDialog(
                    onDismissRequest = { confirmDeleteAll = false },
                    title = stringResource(R.string.settings_delete_offline_all_title),
                    text = { Text(stringResource(R.string.settings_delete_offline_all_body, fmtMb(storage?.let { it.mapsMb + it.routingMb + it.placesMb } ?: 0))) },
                    confirmText = stringResource(R.string.settings_delete_offline_all),
                    onConfirm = { confirmDeleteAll = false; vm.deleteAllOfflineData(); storageScope.launch { kotlinx.coroutines.delay(1500); storageTick++; OfflineMaps.list(context) { regions = it } } },
                    dismissText = stringResource(R.string.settings_cancel),
                    onDismiss = { confirmDeleteAll = false },
                )
            }
            androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { confirmDeleteAll = true },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                ) { Text(stringResource(R.string.settings_delete_offline_all)) }
            }
            Hint(stringResource(R.string.settings_delete_offline_all_hint))
        }
        Spacer(Modifier.height(8.dp))
        // WHAT IS ON THE PHONE, right under the storage figures and the delete button (issue #601):
        // the saved areas and every installed region, one list, so "what do I have" is answered in
        // one place instead of being read out of a 450-row catalog.
        SubHead(stringResource(R.string.settings_downloaded_title))
        LaunchedEffect(Unit) { vm.refreshRoutingRegions() }
        val installedRegions = state.routingRegions.filter { it.id in state.routingInstalledIds }
        val loc = state.myLocation
        val primary = state.routingRegions.filter { r -> loc != null && r.covers(loc.lat, loc.lng) }
            .minByOrNull { (it.n - it.s) * (it.e - it.w) }
        SettingsGroup {
            if (regions.isEmpty() && installedRegions.isEmpty()) {
                Hint(stringResource(R.string.settings_downloaded_none))
            }
            regions.forEachIndexed { ri, r ->
                if (ri > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(OfflineMaps.nameOf(r), style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(stringResource(R.string.settings_downloaded_area), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { OfflineMaps.delete(r) { OfflineMaps.packDatabase(context) { OfflineMaps.list(context) { regions = it } } } }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_offline_delete_area))
                    }
                }
            }
            installedRegions.sortedBy { it.name }.forEachIndexed { ri, region ->
                if (ri > 0 || regions.isNotEmpty()) GroupDivider()
                RegionRow(region, state, vm, primary?.id, indent = false, onConfirm = { confirmRegion = it })
            }
        }
        Spacer(Modifier.height(8.dp))

        SubHead(stringResource(R.string.settings_routing_regions))
        Hint(stringResource(R.string.settings_routing_regions_hint))
        if (state.routingRegions.isEmpty()) {
            Hint(stringResource(R.string.settings_routing_no_regions))
        } else {
            // ONE ALPHABETICAL TREE (user 2026-09-22: the old page led with an "All of <country>"
            // block whose United States entry held three territories, then a flat list of 450 rows
            // with installed and nearby ones pulled to the top). The catalog's hierarchy is in the
            // names: "Bayern (Germany)", "Alberta (Canada)", "Beijing (China)", "Alabama (state)",
            // "Puerto Rico (US)", "Northern California (California)". A parenthetical names the
            // parent; "(state)", "(US)" and "(California)" all sit under the United States. A
            // parent is one row that expands to its pieces and downloads them all in one tap; a
            // country with no pieces is a plain row. Everything sorts by name, the region you are
            // in is marked and its parent starts open.
            val nodes = remember(state.routingRegions) { regionTree(state.routingRegions) }
            var routeFilter by remember { mutableStateOf("") }
            // The field sits low on the page, so the keyboard covered the rows it filters (user
            // 2026-09-22): on focus the page scrolls so the field lands at the TOP of what is left
            // above the keyboard. A rect far taller than the viewport is asked into view, and the
            // scroller aligns its top edge, which is the field.
            val filterReq = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            val filterScope = rememberCoroutineScope()
            if (state.routingRegions.size > 8) {
                OutlinedTextField(
                    value = routeFilter,
                    onValueChange = { routeFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).dpadFieldEscape()
                        .bringIntoViewRequester(filterReq)
                        .onFocusChanged { f ->
                            if (f.isFocused) filterScope.launch {
                                kotlinx.coroutines.delay(350) // the keyboard's own resize first
                                runCatching { filterReq.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 6000f)) }
                            }
                        },
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
            val q = routeFilter.trim()
            val shownNodes = if (q.isBlank()) nodes else nodes.mapNotNull { n ->
                when {
                    n.title.contains(q, ignoreCase = true) -> n
                    n.pieces.size > 1 -> n.pieces.filter { it.name.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { n.copy(listed = it) }
                    else -> null
                }
            }
            if (shownNodes.isEmpty()) {
                Hint(stringResource(R.string.settings_routing_no_match, q))
            }
            val expanded = remember { mutableStateMapOf<String, Boolean>() }
            // THE CATALOG IS A LAZY LIST (user 2026-09-22, "so laggy when I go to offline maps").
            // The page is a plain scrolling Column, and composing and measuring every catalog row
            // at once cost a 430 ms frame on a Pixel 4a release build (Perfetto: 232 ms of measure,
            // 110 ms of recompose); revealing rows a chunk per frame was no better, because a
            // Column re-measures everything on every chunk. A LazyColumn cannot have unbounded
            // height inside a scroller, so the catalog gets the height of the screen and scrolls
            // inside the page once the page has scrolled to it; only the visible rows exist.
            // Parents and their open pieces are flattened into one keyed item list.
            val rows = remember(shownNodes, expanded.toMap(), q, primary?.id) {
                val out = ArrayList<CatalogRow>()
                for (node in shownNodes) {
                    val open = expanded[node.title] ?: (q.isNotBlank() || node.pieces.any { it.id == primary?.id })
                    out += CatalogRow(node, null, open)
                    if (node.parent && open) node.listed.forEach { out += CatalogRow(node, it, open) }
                }
                out
            }
            val catalogHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 160).coerceAtLeast(320).dp
            SettingsGroup {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(catalogHeight)) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { ri, row ->
                        if (ri > 0) GroupDivider()
                        val node = row.node
                        when {
                            row.piece != null -> RegionRow(row.piece, state, vm, primary?.id, indent = true, onConfirm = { confirmRegion = it })
                            !node.parent -> RegionRow(node.pieces[0], state, vm, primary?.id, indent = false, onConfirm = { confirmRegion = it })
                            node.whole != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                // The country's own file carries the row; the chevron opens its pieces.
                                IconButton(onClick = { expanded[node.title] = !row.open }, modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape)) {
                                    Icon(if (row.open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    RegionRow(node.whole, state, vm, primary?.id, indent = false, onConfirm = { confirmRegion = it }, subtitleSuffix = stringResource(R.string.settings_region_whole_or_pieces, node.pieces.size))
                                }
                            }
                            else -> ParentRow(node, state, vm, open = row.open, onToggle = { expanded[node.title] = !row.open })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One row of the lazy catalog: a node's own row ([piece] null) or one of its open pieces. */
internal data class CatalogRow(val node: RegionNode, val piece: app.vela.offline.RoutingRegion?, val open: Boolean) {
    val key: String get() = piece?.id ?: "node:" + node.title
}

/** A catalog entry: one region, or a parent with its pieces ("Germany" over the Laender). [whole]
 *  is the country's own single file when the catalog has both ("Australia" beside its states). */
internal data class RegionNode(
    val title: String,
    val pieces: List<app.vela.offline.RoutingRegion>,
    val whole: app.vela.offline.RoutingRegion? = null,
    /** The pieces the filter left to LIST; the summary and Download all still speak for [pieces]. */
    val listed: List<app.vela.offline.RoutingRegion> = pieces,
) {
    /** A parent stays a parent when the filter leaves it one piece ("Pennsylvania" under United States). */
    val parent: Boolean get() = whole != null || pieces.size > 1 || (pieces.size == 1 && pieces[0].name != title)
}

/** The catalog as parents and leaves, by the names' trailing parentheticals, sorted by title. */
internal fun regionTree(all: List<app.vela.offline.RoutingRegion>): List<RegionNode> {
    val paren = Regex("""\s*\(([^()]+)\)\s*$""")
    val byParent = LinkedHashMap<String, MutableList<app.vela.offline.RoutingRegion>>()
    val leaves = ArrayList<app.vela.offline.RoutingRegion>()
    for (r in all) {
        val p = paren.find(r.name)?.groupValues?.get(1)?.trim()
        val parent = when {
            p == null -> null
            p.equals("state", true) || p.equals("US", true) || p.equals("California", true) -> "United States"
            else -> p
        }
        if (parent == null) leaves += r else byParent.getOrPut(parent) { ArrayList() } += r
    }
    val nodes = ArrayList<RegionNode>()
    val absorbed = HashSet<String>()
    for ((parent, rs) in byParent) {
        if (rs.size >= 2) {
            // A leaf named exactly like the parent is the country's own whole file: one row, not two.
            val whole = leaves.firstOrNull { it.name.equals(parent, ignoreCase = true) }
            if (whole != null) absorbed += whole.id
            nodes += RegionNode(parent, rs.sortedBy { it.name }, whole)
        } else leaves += rs
    }
    leaves.filter { it.id !in absorbed }.forEach { nodes += RegionNode(it.name, listOf(it)) }
    return nodes.sortedBy { it.title.lowercase() }
}

/** A piece's own name under its parent: "Bayern (Germany)" reads "Bayern" in the tree. */
private fun pieceName(r: app.vela.offline.RoutingRegion): String = r.name.replace(Regex("""\s*\([^()]+\)\s*$"""), "")

@Composable
private fun ParentRow(
    node: RegionNode,
    state: app.vela.ui.map.MapUiState,
    vm: MapViewModel,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val pieces = node.pieces
    val missing = pieces.filter { it.id !in state.routingInstalledIds }
    val batchActive = state.regionQueueTotal > 0 && pieces.any { it.id == state.routingDownloadingId }
    val totalMb = pieces.sumOf { p -> regionInstalledMb(p, state.poiPackRegions.firstOrNull { it.id == p.id }, state.regionExtrasMb[p.id] ?: 0) }
    Row(
        Modifier.fillMaxWidth()
            .dpadHighlight(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .dpadClickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            if (open) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(node.title, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                when {
                    batchActive -> stringResource(R.string.settings_region_group_downloading, state.regionQueueTotal - state.regionQueueLeft, state.regionQueueTotal)
                    missing.isEmpty() -> stringResource(R.string.settings_region_group_installed, pieces.size)
                    missing.size < pieces.size -> stringResource(R.string.settings_region_group_partial, pieces.size - missing.size, pieces.size, fmtMb(totalMb))
                    else -> stringResource(R.string.settings_region_group_size, pieces.size, fmtMb(totalMb))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            batchActive -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                androidx.compose.material3.TextButton(
                    onClick = { vm.cancelRegionDownload() },
                    modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                ) { Text(stringResource(R.string.settings_cancel)) }
            }
            missing.isNotEmpty() -> FilledTonalButton(
                onClick = { vm.downloadRoutingGraphs(pieces) },
                enabled = state.routingDownloadingId == null,
                modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
            ) { Text(stringResource(R.string.settings_region_group_download)) }
        }
    }
}

/** One region with its download / progress / update / delete control. */
@Composable
private fun RegionRow(
    region: app.vela.offline.RoutingRegion,
    state: app.vela.ui.map.MapUiState,
    vm: MapViewModel,
    primaryId: String?,
    indent: Boolean,
    onConfirm: (app.vela.offline.RoutingRegion) -> Unit,
    subtitleSuffix: String? = null,
) {
    val installed = region.id in state.routingInstalledIds
    val downloading = state.routingDownloadingId == region.id
    val packDownloading = state.poiPackDownloadingId == region.id
    val packInstalled = region.id in state.poiPackInstalledIds
    // A fresher pack is published than the one installed → offer an in-place update
    // (a small row-level delta when the manifest carries one, else a full re-download).
    val packRegion = state.poiPackRegions.firstOrNull { it.id == region.id }
    // A newer bake of the pack, the places, the map or the routing file: one Update.
    val updateAvailable = (installed && packInstalled && packRegion != null &&
        packRegion.rev > (state.poiPackInstalledRevs[region.id] ?: 0)) ||
        state.regionUpdates.containsKey(region.id)
    val here = region.id == primaryId
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 40.dp else if (subtitleSuffix != null) 0.dp else 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (indent) pieceName(region) else region.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                (if (subtitleSuffix != null) "$subtitleSuffix · " else "") + when {
                    downloading -> stringResource(R.string.settings_routing_downloading, state.routingDownloadPct)
                    packDownloading -> stringResource(R.string.settings_routing_places_downloading, state.poiPackDownloadPct)
                    updateAvailable -> stringResource(R.string.settings_routing_update_available)
                    installed && packInstalled -> stringResource(R.string.settings_routing_installed_places)
                    installed -> stringResource(R.string.settings_routing_installed)
                    here -> stringResource(R.string.settings_routing_size_installed_here, fmtMb(regionInstalledMb(region, packRegion, state.regionExtrasMb[region.id] ?: 0)))
                    else -> stringResource(R.string.settings_routing_size_installed, fmtMb(regionInstalledMb(region, packRegion, state.regionExtrasMb[region.id] ?: 0)))
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
            downloading || packDownloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                DpadFocusHandoff(keeper)
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                androidx.compose.material3.TextButton(
                    onClick = { vm.cancelRegionDownload() },
                    modifier = Modifier.dpadFocusKept(keeper).dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                ) { Text(stringResource(R.string.settings_cancel)) }
            }
            updateAvailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                DpadFocusHandoff(keeper)
                FilledTonalButton(
                    onClick = { vm.updateRegion(region) },
                    enabled = state.routingDownloadingId == null && state.poiPackDownloadingId == null,
                    modifier = Modifier.dpadFocusKept(keeper),
                ) { Text(stringResource(R.string.settings_update_region)) }
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
                        if (regionInstalledMb(region, packRegion, state.regionExtrasMb[region.id] ?: 0) > CONFIRM_MB) onConfirm(region)
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

/** One "label ..... size" line in the storage group. */
@Composable
private fun StorageRow(label: String, mb: Int, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.dpadHighlight(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).dpadClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(fmtMb(mb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The size a region really lands at: manifest installedMb when the bake published it, else the
 *  download size for an obf (it installs as-is) and ~2.35x the zip for a pack (the state pack measured
 *  143 -> 335 MB). The obf, its place pack and [extraMb] (the places archive and offline map the
 *  same download pulls, MapUiState.regionExtrasMb) install together, so the shown number is their SUM. */
internal fun regionInstalledMb(graph: app.vela.offline.RoutingRegion, pack: app.vela.offline.RoutingRegion?, extraMb: Int = 0): Int {
    val g = if (graph.installedMb > 0) graph.installedMb else graph.sizeMb
    val p = pack?.let { if (it.installedMb > 0) it.installedMb else (it.sizeMb * 2.35).toInt() } ?: 0
    return g + p + extraMb
}

internal fun fmtMb(mb: Int): String =
    if (mb >= 1024) String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024f) else "$mb MB"

private const val CONFIRM_MB = 1024
