import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.Toll;
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
    // toll + road_class power the car_avoid_toll / car_avoid_motorway profiles (2026-07-11).
    // Adding EVs is a BREAKING graph-format change: the app engine try-loads new-then-old
    // strings, so graphs baked before this line keep working (without the avoid profiles).
    static final String ENCODED_VALUES = "car_access, car_average_speed, road_access, max_speed, toll, road_class";

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
                    // The avoid profiles block their road class outright (infinite weight) on top of
                    // the base access block. CH is prepared PER PROFILE, so each bakes its own
                    // weighting - must stay identical to GraphHopperRouteEngine's factory.
                    String name = profile.getName();
                    boolean avoidToll = name.equals("car_avoid_toll");
                    boolean avoidMotorway = name.equals("car_avoid_motorway");
                    EnumEncodedValue<Toll> toll = avoidToll ? getEncodingManager().getEnumEncodedValue("toll", Toll.class) : null;
                    EnumEncodedValue<RoadClass> roadClass = avoidMotorway ? getEncodingManager().getEnumEncodedValue("road_class", RoadClass.class) : null;
                    return new SpeedWeighting(speed) {
                        @Override
                        public double calcEdgeWeight(EdgeIteratorState e, boolean reverse) {
                            boolean ok = reverse ? e.getReverse(access) : e.get(access);
                            if (!ok) return Double.POSITIVE_INFINITY;
                            // Toll.ALL = tolls every vehicle pays; HGV-only tolls stay routable for cars.
                            if (toll != null && e.get(toll) == Toll.ALL) return Double.POSITIVE_INFINITY;
                            if (roadClass != null && e.get(roadClass) == RoadClass.MOTORWAY) return Double.POSITIVE_INFINITY;
                            return super.calcEdgeWeight(e, reverse);
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
        hopper.setProfiles(
                new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")),
                new Profile("car_avoid_toll").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")),
                new Profile("car_avoid_motorway").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        hopper.getCHPreparationHandler().setCHProfiles(
                new CHProfile("car"), new CHProfile("car_avoid_toll"), new CHProfile("car_avoid_motorway"));
        hopper.importOrLoad();
        System.out.println("built " + args[1] + " from " + args[0] + " in " + (System.currentTimeMillis() - t0) + " ms");
        // bbox for the region's manifest entry ([S,W,N,E] — the order RoutingGraphStore/engine expect).
        com.graphhopper.util.shapes.BBox bb = hopper.getBaseGraph().getBounds();
        System.out.printf("manifest bbox [S,W,N,E] = [%.5f, %.5f, %.5f, %.5f]%n", bb.minLat, bb.minLon, bb.maxLat, bb.maxLon);

        // sanity: the built CH graph must route quickly (coords default to a a mid-size trip; override
        // with args[2..5] = fromLat fromLon toLat toLon to smoke a route inside THIS region).
        double fLat = 38.55, fLon = -121.74, tLat = 38.58, tLon = -121.49;
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
                // And REFS/DESTINATIONS? (the shield badge + "toward" text read extraInfo street_ref /
                // street_destination / motorway_junction — stored whenever parseWayNames is on, the default)
                int named = 0, reffed = 0, total = 0;
                for (Instruction ins : rs.getBest().getInstructions()) {
                    total++;
                    String nm = ins.getName();
                    if (nm != null && !nm.isEmpty()) named++;
                    Object ref = ins.getExtraInfoJSON().get("street_ref");
                    if (ref != null && !ref.toString().isEmpty()) reffed++;
                    if (total <= 8) System.out.println("    instr sign=" + ins.getSign() + " name='" + nm
                            + "' extra=" + ins.getExtraInfoJSON());
                }
                System.out.println("NAMED INSTRUCTIONS: " + named + "/" + total
                        + (named == 0 ? "  <<< NO STREET NAMES IN GRAPH" : ""));
                System.out.println("REF-CARRYING INSTRUCTIONS: " + reffed + "/" + total
                        + " (0 is fine for a route with no signed highways)");
            }
        } catch (Exception e) {
            System.out.println("route smoke check skipped: " + e.getMessage());
        }
        hopper.close();
    }
}
