// A newer R8 than AGP 8.10 bundles: Chromium now compiles Cronet's jars as Java 25 class files
// (major 69), which the bundled R8 refuses ("Unsupported class file major version 69"). Pinning R8
// on the buildscript classpath is the supported way to take a newer shrinker without moving AGP.
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.4.26") }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
