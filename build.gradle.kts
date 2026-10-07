// Newer versions of libraries the BUILD TOOLS bring along (the Android Gradle plugin and friends).
// None of these is in the app; they run on the build machine. The versions the plugins ask for
// have published vulnerabilities, so the plugin classpath is held to the first fixed release of
// each. Drop a line when the plugins themselves ask for that version or newer.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.85")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85")
            classpath("org.bouncycastle:bcutil-jdk18on:1.85")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-lang3:3.18.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// The same holds for two classpaths the block above does not reach. Android lint resolves its own
// tool classpath in every module (androidLintTool), and the baseline-profile generator's trace
// reader brings wire-runtime into that test module. Neither is in the app.
subprojects {
    configurations.matching { it.name == "androidLintTool" }.configureEach {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
        )
    }
    if (name == "baselineprofile") {
        configurations.configureEach {
            resolutionStrategy.force(
                "com.squareup.wire:wire-runtime:6.4.5",
                "com.squareup.wire:wire-runtime-jvm:6.4.5",
            )
        }
    }
}

