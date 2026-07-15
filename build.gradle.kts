plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}

// Dead-code static analysis. detekt is applied to the two SHIPPED modules only (:app, :core);
// the config (config/detekt/detekt.yml) enables ONLY delegate-aware dead-code rules (unused
// imports / private members / unreachable code) and turns everything else off, so it never
// flags style/complexity noise. The cross-module + dead-resource + dead-module half lives in
// tests/dead_code/audit_deadcode.sh. Run both locally: ./gradlew detekt && bash
// tests/dead_code/audit_deadcode.sh
subprojects {
    if (name == "app" || name == "core") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = false
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel = true
        }
    }
}
