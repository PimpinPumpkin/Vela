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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
