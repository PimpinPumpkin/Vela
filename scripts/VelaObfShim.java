import net.osmand.MainUtilities;
import net.osmand.obf.preparation.IndexCreatorSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a Vela obf. Default: the ROUTING section only, with the indexing passes routing does not
 * need switched off (multipolygons, route relations, proximity, country regions). Measured
 * 2026-09-04 on the same 345 MB Washington extract: the full routing+address+POI bake dies in
 * MapCreator's first pass at a 12 GB heap (as does routing-only with the default passes on), the
 * lean routing-only bake completes at 12 GB in 48 minutes and produces a 111 MB obf, against
 * 595 MB for OsmAnd's own roads-only file of the same state. That is what lets a free 16 GB
 * runner bake a US state at all. The engine (ObfRouteEngine) reads only the routing section.
 *
 * VELA_OBF_SECTIONS=routing,address,poi restores the phase-2 sections (offline search on obf),
 * at the old memory cost; VELA_OBF_LEAN=false restores MapCreator's default passes.
 * MainUtilities' CLI has no --no-map/--no-poi switches, only the settings object does.
 * Writes <Region>.obf next to the input, exactly like `generate-obf` would.
 */
public class VelaObfShim {
    public static void main(String[] args) throws Exception {
        IndexCreatorSettings settings = new IndexCreatorSettings();
        String sections = System.getenv("VELA_OBF_SECTIONS") == null ? "routing" : System.getenv("VELA_OBF_SECTIONS");
        boolean lean = !"false".equals(System.getenv("VELA_OBF_LEAN"));
        settings.indexMap = false;
        settings.indexTransport = false;
        settings.indexRouting = sections.contains("routing");
        settings.indexAddress = sections.contains("address");
        settings.indexPOI = sections.contains("poi");
        if (lean) {
            settings.indexMultipolygon = false;
            settings.indexByProximity = false;
            settings.indexCountryRegions = false;
            // Route relations stay ON even in the lean bake (2026-09-12): they carry the signed
            // cycle-route membership OsmAnd's bicycle profile prefers, and with the roads-only
            // pre-filter they cost 13% of the bake time and 0.4 MB on a US state (Washington:
            // 300 s / 87.4 MB -> 339 s / 87.8 MB). VELA_OBF_ROUTE_RELATIONS=false turns them off.
            settings.indexRouteRelations = !"false".equals(System.getenv("VELA_OBF_ROUTE_RELATIONS"));
        }
        System.out.println("vela obf: sections=" + sections + " lean=" + lean);
        List<String> a = new ArrayList<>();
        a.add(args[0]);
        MainUtilities.generateObf(a, settings);
    }
}
