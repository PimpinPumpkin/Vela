plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.vela.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.rhino.runtime)
    // On-device routing/map-matching engine (see RouteEngine + ROADMAP). The OSM-IMPORT-only
    // transitive deps are Android-hostile (AWT/StAX) and unused at runtime — we ship prebuilt
    // graphs and only LOAD + route + match on-device — so they're excluded (proven via :ghprobe).
    implementation(libs.graphhopper.mapmatching) {
        exclude(group = "org.openstreetmap.osmosis")
        exclude(group = "com.google.protobuf")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "com.fasterxml.woodstox")
        exclude(group = "org.codehaus.woodstox")
        exclude(group = "org.apache.xmlgraphics")
    }

    // OsmAnd obf routing engine (ObfRouteEngine): the router + binary obf reader as plain Java
    // jars, vendored from OsmAndMapCreator's lib (GPLv3, same license as Vela). Gitignored like
    // the sherpa AAR - CI fetches them from the `obf-runtime` infra release; locally copy them in
    // (see CLAUDE.md). Everything else the router needs is already on Android (org.json,
    // kotlin-stdlib) or in this module (kotlinx-serialization). commons-logging + kxml2 are the
    // two desktop assumptions Android does NOT satisfy: OsmAnd logs through commons-logging and
    // instantiates org.kxml2.io.KXmlParser directly (present on a desktop classpath, not visible
    // to apps on modern Android) - both device-caught on the release canary, 2026-07-23.
    // kxml2-vela.jar is upstream kxml2 2.3.0 with its bundled org/xmlpull/** REMOVED - the stock
    // Maven jar duplicates the platform's XmlPullParser interfaces and R8 hard-fails on the
    // library/program split ("Library class android.content.res.XmlResourceParser implements
    // program class org.xmlpull.v1.XmlPullParser").
    implementation(files("libs/osmand-java.jar", "libs/osmand-shared-jvm.jar", "libs/gnu-trove-osmand.jar", "libs/kxml2-vela.jar"))
    implementation("commons-logging:commons-logging:1.2")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

// Forward the trip-audit harness property into the TEST JVM (see NavReplayTest.auditSharedTripLog):
// `-DvelaTrip=…` on the command line sets a GRADLE-daemon property, which the forked test JVM does
// NOT inherit — without this the documented audit command silently skipped the test every time.
tasks.withType<Test>().configureEach {
    System.getProperty("velaTrip")?.let { systemProperty("velaTrip", it) }
}
