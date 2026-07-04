import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.weighting.SpeedWeighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Instruction;

/**
 * Builds an on-device GraphHopper graph for one region — the off-device half of Vela's offline
 * routing (see app `GraphHopperRouteEngine` + ROADMAP "On-device map-matching"). CI runs this per
 * region and ships the output folder as a release asset; the app downloads + loads it.
 *
 * It MUST stay byte-for-byte config-compatible with the engine that loads the graph:
 *   - encoded values: car_access, car_average_speed, road_access, max_speed
 *     (max_speed = OSM `maxspeed` posted limit, km/h, stored per edge; a passive column read by the
 *      app's speed-limit badge — NOT used for routing/CH, so it doesn't change the baked weighting)
 *   - profile: "car" (car.json custom model, metadata only)
 *   - weighting: a Janino-free SpeedWeighting + access block (ART can't run GraphHopper's Janino-
 *     compiled custom-model weighting), and **Contraction Hierarchies are prepared on that same
 *     weighting** (mandatory — CH bakes the build-time weighting; mismatched query weighting = wrong
 *     routes). CH is what makes on-device routing ~tens of ms instead of ~7 s of flexible A*.
 *
 * Usage: gradlew run --args="<region.osm.pbf> <out-graph-dir>"
 * Build region extracts with: osmium extract -b <W,S,E,N> <state>.osm.pbf -o <region>.osm.pbf
 */
public class GraphBuilder {
    static final String ENCODED_VALUES = "car_access, car_average_speed, road_access, max_speed";

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: <region.osm.pbf> <out-graph-dir>");
            System.exit(2);
        }
        long t0 = System.currentTimeMillis();
        GraphHopper hopper = new GraphHopper() {
            @Override
            protected WeightingFactory createWeightingFactory() {
                return (profile, hints, disableTurnCosts) -> {
                    DecimalEncodedValue speed = getEncodingManager().getDecimalEncodedValue("car_average_speed");
                    BooleanEncodedValue access = getEncodingManager().getBooleanEncodedValue("car_access");
                    return new SpeedWeighting(speed) {
                        @Override
                        public double calcEdgeWeight(EdgeIteratorState e, boolean reverse) {
                            boolean ok = reverse ? e.getReverse(access) : e.get(access);
                            return ok ? super.calcEdgeWeight(e, reverse) : Double.POSITIVE_INFINITY;
                        }

                        // car_average_speed is km/h; SpeedWeighting reports time as if it were m/s
                        // (3.6x too fast). Report real ms — must stay identical to GraphHopperRouteEngine.
                        @Override
                        public long calcEdgeMillis(EdgeIteratorState e, boolean reverse) {
                            double kmh = reverse ? e.getReverse(speed) : e.get(speed);
                            return kmh <= 0 ? Long.MAX_VALUE : (long) (e.getDistance() * 3600.0 / kmh);
                        }
                    };
                };
            }
        };
        hopper.setOSMFile(args[0]);
        hopper.setGraphHopperLocation(args[1]);
        hopper.setEncodedValuesString(ENCODED_VALUES);
        hopper.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
        hopper.importOrLoad();
        System.out.println("built " + args[1] + " from " + args[0] + " in " + (System.currentTimeMillis() - t0) + " ms");
        // bbox for the region's manifest entry ([S,W,N,E] — the order RoutingGraphStore/engine expect).
        com.graphhopper.util.shapes.BBox bb = hopper.getBaseGraph().getBounds();
        System.out.printf("manifest bbox [S,W,N,E] = [%.5f, %.5f, %.5f, %.5f]%n", bb.minLat, bb.minLon, bb.maxLat, bb.maxLon);

        // sanity: the built CH graph must route quickly (coords default to a Puget-Sound trip; override
        // with args[2..5] = fromLat fromLon toLat toLon to smoke a route inside THIS region).
        double fLat = 47.86, fLon = -122.20, tLat = 47.66, tLon = -122.30;
        if (args.length >= 6) {
            fLat = Double.parseDouble(args[2]); fLon = Double.parseDouble(args[3]);
            tLat = Double.parseDouble(args[4]); tLon = Double.parseDouble(args[5]);
        }
        try {
            long t = System.currentTimeMillis();
            GHResponse rs = hopper.route(new GHRequest(fLat, fLon, tLat, tLon).setProfile("car"));
            if (rs.hasErrors()) System.out.println("route smoke check: " + rs.getErrors() + " (ok if outside this region)");
            else {
                System.out.println("route smoke check: " + Math.round(rs.getBest().getDistance() / 1609.0)
                        + " mi in " + (System.currentTimeMillis() - t) + " ms (CH)");
                // Do the built instructions carry STREET NAMES? (offline turn-by-turn reads Instruction.getName())
                int named = 0, total = 0;
                for (Instruction ins : rs.getBest().getInstructions()) {
                    total++;
                    String nm = ins.getName();
                    if (nm != null && !nm.isEmpty()) named++;
                    if (total <= 8) System.out.println("    instr sign=" + ins.getSign() + " name='" + nm + "'");
                }
                System.out.println("NAMED INSTRUCTIONS: " + named + "/" + total
                        + (named == 0 ? "  <<< NO STREET NAMES IN GRAPH" : ""));
            }
        } catch (Exception e) {
            System.out.println("route smoke check skipped: " + e.getMessage());
        }
        hopper.close();
    }
}
