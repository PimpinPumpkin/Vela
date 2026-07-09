import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
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
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.0.1"

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

        // Offline-routing region manifest (lists the prebuilt per-region CH graphs to download).
        // Default = the latest GitHub release's asset; override for local testing with
        // -ProutingManifestUrl=http://127.0.0.1:8099/manifest.json (served via `adb reverse`).
        buildConfigField(
            "String",
            "ROUTING_MANIFEST_URL",
            "\"${(project.findProperty("routingManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/routing-graphs/routing-manifest.json"}\"",
        )
        // Open building-footprint overlay (Microsoft, ODbL) PMTiles catalog — same override pattern
        // (-PoverlayManifestUrl=http://127.0.0.1:8099/... for local testing via `adb reverse`).
        buildConfigField(
            "String",
            "OVERLAY_MANIFEST_URL",
            "\"${(project.findProperty("overlayManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/building-overlays/building-overlay-manifest.json"}\"",
        )
        // Open house-number (address-point) overlay (OpenAddresses) PMTiles catalog — same override pattern
        // (-PaddressManifestUrl=…). Rendered as a SymbolLayer of house numbers where OSM lacks addr:housenumber.
        buildConfigField(
            "String",
            "ADDRESS_MANIFEST_URL",
            "\"${(project.findProperty("addressManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/address-overlays/address-overlay-manifest.json"}\"",
        )
        // Offline PLACE packs (whole-region POI/address SQLite, pulled with a routing-region download so a
        // state is searchable offline) — same override pattern (-PpoiPackManifestUrl=… via `adb reverse`).
        buildConfigField(
            "String",
            "POI_PACK_MANIFEST_URL",
            "\"${(project.findProperty("poiPackManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/poi-packs/poi-pack-manifest.json"}\"",
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
        debug {
            // R8 is what keeps map scroll/nav smooth — MapLibre draws natively (unaffected by
            // `debuggable`), so the jank is the Compose/Kotlin overlay, which R8 optimizes. Keeping
            // `debuggable=true` (AGP default) means breakpoints + Timber/StrictMode still work; only
            // the build gets slower (R8 runs each time). `proguard-rules.pro` is MANDATORY here or
            // sherpa-onnx's FindClass-by-name JNI SIGABRTs; `proguard-rules-debug.pro` keeps stacks
            // readable (-dontobfuscate). Use the `staging` variant below for true non-debuggable perf.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro",
            )
        }
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
        // Non-debuggable, release-optimized variant for measuring the TRUE production frame profile
        // (Perfetto / `dumpsys gfxinfo framestats`) without the small `debuggable` ART deopt. Not
        // attachable to a debugger — use `debug` for stepping, `staging` for "is it actually smooth".
        create("staging") {
            initWith(getByName("release"))
            isDebuggable = false
            applicationIdSuffix = ".staging"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.timber)

    // MapLibre Native — the renderer. Only the app module touches it; :core
    // stays UI-agnostic.
    implementation(libs.maplibre.android)
    implementation(libs.androidx.car.app) // Android Auto (projection): templates + car surface

    debugImplementation(libs.androidx.compose.ui.tooling)
}
