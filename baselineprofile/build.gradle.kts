// Baseline-profile GENERATOR module. Runs the app on the emulator below, records the classes and
// methods the hot paths touch (startup, map pan, Settings), and writes them to
// app/src/release/generated/baselineProfiles/, which is committed. Every release build bakes
// the file in, and androidx.profileinstaller hands it to the system on first launch so those
// paths are compiled ahead of time. Sideloaded installs get no Play cloud profiles.
//
// Regenerate: ./gradlew :app:generateBaselineProfile
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.vela.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    targetProjectPath = ":app"

    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp"
    }
}

// Generation runs on a Gradle-managed EMULATOR, never a connected phone: the test harness
// UNINSTALLS the target app when it finishes, which on a real device nukes saved places, trips
// and permission grants (it did, once - 2026-07-16). An emulator is disposable, and the same
// invocation runs headless in CI (ubuntu runners have KVM).
baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
