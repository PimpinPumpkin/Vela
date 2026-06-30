// Standalone JVM tool — NOT part of the app build (it pulls GraphHopper's heavy OSM-import deps).
// Run it on a dev box or in CI to build a per-region routing graph for the app to download.
//   ./gradlew run --args="seattle.osm.pbf seattle-graph"
plugins { application }
// repositories are centrally managed (root settings.gradle.kts, FAIL_ON_PROJECT_REPOS)
dependencies { implementation("com.graphhopper:graphhopper-map-matching:11.0") }
application {
    mainClass.set("GraphBuilder")
    // Imports are memory-heavy; 12g lets country-sized regions build on a 16 GB CI runner.
    // The very largest (Germany/France) may still need splitting into Geofabrik subregions.
    applicationDefaultJvmArgs = listOf("-Xmx12g")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
