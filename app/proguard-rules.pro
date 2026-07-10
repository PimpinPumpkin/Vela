# R8/ProGuard rules for the Vela app.
#
# MapLibre, Compose, Hilt and Coil all ship their own consumer rules. :core
# contributes the kotlinx.serialization keeps. The only thing we add here is a
# belt-and-suspenders keep on our model package, whose enum names show up in
# persisted nav state.
-keepnames class app.vela.core.model.** { *; }

# sherpa-onnx neural-TTS runtime (vendored AAR). Its JNI resolves the Kotlin config classes AND
# their fields by their ORIGINAL fully-qualified names (FindClass / GetFieldID) at OfflineTts init.
# R8 renaming/stripping them makes the native side throw ClassNotFoundError and SIGABRT the process
# - the AAR ships no consumer rules, so we keep the whole package (classes + members) un-renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Timber: physically strip verbose/debug calls (call site + argument building) from the RELEASE dex.
# WARN/ERROR are deliberately NOT stripped - they feed the opt-in on-device diagnostics ring
# (DiagTree → DiagLog → crash/ANR reports). This file is release-only; the debug variant keeps them.
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
}

# kotlinx.serialization (also in :core consumer rules; harmless to repeat).
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class **.Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
