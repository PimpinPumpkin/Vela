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
        // them from the run number → 0.2.<run> / 2000+run). Defaults are local/dev only.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.2.0"

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
    }
}

dependencies {
    implementation(project(":core"))

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

    debugImplementation(libs.androidx.compose.ui.tooling)
}
