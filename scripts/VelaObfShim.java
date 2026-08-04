import net.osmand.MainUtilities;
import net.osmand.obf.preparation.IndexCreatorSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a Vela obf: routing + address + POI sections only. The map-rendering and transport
 * sections are excluded on purpose (MapLibre draws Vela's map; transit comes from GTFS), which is
 * most of the size difference against a stock OsmAnd file. Compiled and run by
 * scripts/build-obf-region.sh against the OsmAndMapCreator jars; kept as a shim because
 * MainUtilities' CLI has no --no-map/--no-poi switches, only the settings object does.
 *
 * Usage: java VelaObfShim <region.osm.pbf>
 * Writes <Region>.obf next to the input, exactly like `generate-obf` would.
 */
public class VelaObfShim {
    public static void main(String[] args) throws Exception {
        IndexCreatorSettings settings = new IndexCreatorSettings();
        settings.indexMap = false;
        settings.indexTransport = false;
        settings.indexRouting = true;
        settings.indexAddress = true;
        settings.indexPOI = true;
        List<String> a = new ArrayList<>();
        a.add(args[0]);
        MainUtilities.generateObf(a, settings);
    }
}
