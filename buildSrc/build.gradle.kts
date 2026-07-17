// buildSrc exists for ONE thing: the GraphHopper ByteBuffer patch transform (see
// src/main/kotlin/GraphHopperByteBufferPatch.kt). Plain Gradle + ASM - deliberately NO Android
// Gradle Plugin dependency here: AGP on the buildSrc classpath splits the plugin classes across
// classloaders and breaks the root plugins block.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
}
