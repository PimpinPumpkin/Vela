// OsmAnd's router jar (core/libs/osmand-java.jar, fetched by CI from the obf-runtime release) bundles
// an old protobuf runtime under the standard com.google.protobuf package. Cronet needs the modern
// lite runtime under the same names: two libraries under one name either fail the build (duplicate
// classes) or crash at runtime (OsmAnd's copy lacks MapFieldLite). This module rewrites the jar at
// build time so OsmAnd's copy, and every reference OsmAnd makes to it, lives under
// net.osmand.shaded.protobuf. The jar on the release is untouched; :core depends on this output.
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(files("../core/libs/osmand-java.jar"))
}

tasks.shadowJar {
    archiveFileName.set("osmand-java-relocated.jar")
    relocate("com.google.protobuf", "net.osmand.shaded.protobuf")
}

// Nothing of this module's own: it is only the relocated jar.
tasks.jar { enabled = false }
