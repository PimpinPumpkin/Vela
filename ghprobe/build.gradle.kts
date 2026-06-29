// THROWAWAY probe module — proves (or disproves) GraphHopper v11 routing + map-matching on
// real Android/ART. Instrumented test only; no main code. Delete once the decision is made.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.vela.ghprobe"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    androidTestImplementation("com.graphhopper:graphhopper-map-matching:11.0") {
        // OSM-IMPORT-only + Android-hostile transitive deps — we LOAD a prebuilt graph on-device,
        // never import .pbf here, so these aren't on the runtime path. (Janino is deliberately KEPT
        // — the custom-model compiler is exactly what we're testing on ART.)
        exclude(group = "org.openstreetmap.osmosis")
        exclude(group = "com.google.protobuf")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "com.fasterxml.woodstox")
        exclude(group = "org.codehaus.woodstox")
        exclude(group = "org.apache.xmlgraphics")
    }
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
