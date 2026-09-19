import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

// android.util.Log and friends THROW "not mocked" in a JVM unit test otherwise, which turns a
// logging line inside a runCatching into a mysterious failure of the thing being tested (cost an
// afternoon on the delta applier). Same setting :core has had for the obf engine's logging.
android.testOptions.unitTests.isReturnDefaultValues = true

dependencies {
    testImplementation(libs.junit) // app-module unit tests (SearchGatesTest, DiagScrubTest)
    // Android's org.json is a STUB on the unit-test classpath: every method throws "not mocked",
    // so code that parses JSON (the delta patch header) cannot be tested without a real one.
    testImplementation("org.json:json:20240303")
}

android {
    namespace = "app.vela"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vela"
        minSdk = 26
        targetSdk = 35
        // Overridable from CI: -PappVersionCode / -PappVersionName (ci.yml derives
        // them from the run number → 0.3.<run> / 2000+run). Defaults are local/dev only.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // MapTiler key injected from the CI secret (-PmaptilerKey); empty for
        // local builds, in which case the app falls back to the keyless
        // OpenFreeMap basemap. Never stored in the repo.
        buildConfigField(
            "String",
            "MAPTILER_KEY",
            "\"${(project.findProperty("maptilerKey") as String?) ?: ""}\"",
        )

        // Obf region catalog (issue #214): the one offline-routing catalog since the GraphHopper
        // graphs were retired (2026-09-15). Override for
        // local testing with -PobfManifestUrl=http://127.0.0.1:8099/obf-manifest.json (adb reverse).
        buildConfigField(
            "String",
            "OBF_MANIFEST_URL",
            "\"${(project.findProperty("obfManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/obf-regions/obf-manifest.json"}\"",
        )
        // Open building-footprint overlay (Microsoft, ODbL) PMTiles catalog — same override pattern
        // (-PoverlayManifestUrl=http://127.0.0.1:8099/... for local testing via `adb reverse`).
        buildConfigField(
            "String",
            "OVERLAY_MANIFEST_URL",
            "\"${(project.findProperty("overlayManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/building-overlays/building-overlay-manifest.json"}\"",
        )
        // Posted speed-limit overlay (OSM maxspeed, ODbL) PMTiles catalog — the "Speed B" online source that
        // shows a limit WITHOUT the offline routing graph. Same override pattern (-PmaxspeedManifestUrl=…).
        buildConfigField(
            "String",
            "MAXSPEED_MANIFEST_URL",
            "\"${(project.findProperty("maxspeedManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/maxspeed-overlays/maxspeed-overlay-manifest.json"}\"",
        )
        // Open house-number (address-point) overlay (OpenAddresses) PMTiles catalog — same override pattern
        // (-PaddressManifestUrl=…). Rendered as a SymbolLayer of house numbers where OSM lacks addr:housenumber.
        buildConfigField(
            "String",
            "ADDRESS_MANIFEST_URL",
            "\"${(project.findProperty("addressManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/address-overlays/address-overlay-manifest.json"}\"",
        )
        // Open-data PLACES layer (Overture Places baked to PMTiles, tools/build-places-region.sh) catalog,
        // same override pattern (-PplacesManifestUrl=…). Drawn like the Google ambient dots; Google is asked on tap.
        buildConfigField(
            "String",
            "PLACES_MANIFEST_URL",
            "\"${(project.findProperty("placesManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/places-overlays/places-overlay-manifest.json"}\"",
        )
        // Offline BASEMAP tiles (planetiler bakes of the Geofabrik extracts in the OpenMapTiles schema,
        // .github/workflows/basemap-tiles.yml) catalog, same override pattern (-PbasemapManifestUrl=...).
        // An installed archive replaces the style's tile source where it covers the view.
        buildConfigField(
            "String",
            "BASEMAP_MANIFEST_URL",
            "\"${(project.findProperty("basemapManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/basemap-tiles/basemap-manifest.json"}\"",
        )
        // Offline PLACE packs (whole-region POI/address SQLite, pulled with a routing-region download so a
        // state is searchable offline) — same override pattern (-PpoiPackManifestUrl=… via `adb reverse`).
        buildConfigField(
            "String",
            "POI_PACK_MANIFEST_URL",
            "\"${(project.findProperty("poiPackManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/poi-packs/poi-pack-manifest.json"}\"",
        )
        // ALPR/Flock surveillance-camera dataset (DeFlock/OSM). A bundled floor ships in assets/, and the
        // app refreshes from this hosted manifest so camera data updates WITHOUT an app release (weekly CI
        // cron re-bakes + re-hosts). Same override pattern (-PflockManifestUrl=… via `adb reverse`).
        buildConfigField(
            "String",
            "FLOCK_MANIFEST_URL",
            "\"${(project.findProperty("flockManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/flock-cameras/flock-manifest.json"}\"",
        )
        // Per-region road features (lights, stop signs, crossings, humps, speed cameras) baked on CI
        // from Geofabrik extracts and hosted on the `road-features` release; the app downloads the
        // file for the region it is in instead of querying Overpass (issue #304). Same override
        // pattern (-ProadFeaturesManifestUrl=… via `adb reverse`).
        buildConfigField(
            "String",
            "ROAD_FEATURES_MANIFEST_URL",
            "\"${(project.findProperty("roadFeaturesManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/road-features/road-features-manifest.json"}\"",
        )
        // Self-hosted map-font glyphs (Roboto composited over Noto; see ui/map/MapFonts) served
        // from the repo's GitHub Pages — same override pattern (-PmapFontsUrl=http://127.0.0.1:8099
        // via `adb reverse` against a local `python3 -m http.server` on the glyph directory).
        buildConfigField(
            "String",
            "MAP_FONTS_URL",
            "\"${(project.findProperty("mapFontsUrl") as String?)
                ?: "https://pimpinpumpkin.github.io/Vela/fonts"}\"",
        )
    }

    // Real release signing comes from CI env vars; local dev falls back to the
    // debug keystore so `adb install` still works.
    //   VELA_KEYSTORE_PATH / VELA_KEYSTORE_PASSWORD / VELA_KEY_ALIAS (=vela)
    signingConfigs {
        create("releaseFromEnv") {
            val path = System.getenv("VELA_KEYSTORE_PATH")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("VELA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VELA_KEY_ALIAS") ?: "vela"
                keyPassword = System.getenv("VELA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Always ship release: R8 here is what keeps map scroll/nav smooth
            // (debug builds visibly lag).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val envSigning = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (envSigning.storeFile?.exists() == true) {
                envSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The neural-TTS runtime (ONNX Runtime + sherpa-onnx, from the vendored AAR) ships its .so
        // for all 4 ABIs; Vela targets arm64 phones, so drop the other ABIs' copies — they'd add
        // ~65 MB for no device we support. MapLibre and other libs stay multi-ABI (untouched).
        jniLibs {
            excludes += listOf(
                "**/armeabi-v7a/libonnxruntime.so", "**/armeabi-v7a/libsherpa-onnx*.so",
                "**/x86/libonnxruntime.so", "**/x86/libsherpa-onnx*.so",
                "**/x86_64/libonnxruntime.so", "**/x86_64/libsherpa-onnx*.so",
            )
        }
    }
}

dependencies {
    // Bakes the committed baseline profile into the APK and AOT-compiles it at install time -
    // the fix for sideloaded nightlies running interpreter-cold until overnight dexopt.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(project(":core"))

    // sherpa-onnx: in-process neural TTS runtime (runs the downloaded Kokoro model). Vendored AAR
    // (no official Maven artifact; the JitPack coordinate doesn't resolve). Lives in :app because a
    // library module can't consume a local .aar — KokoroSynth sits in :app and bridges into :core's
    // VoiceGuide via an interface. Native .so are arm64-only in the package (see packaging{}).
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))
    // Extracts the Kokoro model's .tar.bz2 at download time (Android has no built-in bzip2/tar).
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)

    // MapLibre Native — the renderer. Only the app module touches it; :core
    // stays UI-agnostic.
    implementation(libs.maplibre.android)
    implementation(libs.androidx.car.app) // Android Auto (projection): templates + car surface
    implementation(libs.androidx.car.app.projected) // projected host connector (phone → car)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// On-demand harnesses need their -D properties in the test JVM; Gradle does not forward them
// (the same trap core/build.gradle.kts documents for velaTrip).
tasks.withType<Test>().configureEach {
    listOf("velaPmtiles", "velaLat", "velaLng", "velaArchive", "velaPatch", "velaFingerprint").forEach { k ->
        System.getProperty(k)?.let { systemProperty(k, it) }
    }
}
