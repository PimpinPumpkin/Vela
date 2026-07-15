# Consumer R8 rules contributed by :core to whatever app depends on it.
#
# We parse Google's responses positionally (no reflective field names), so the
# model classes don't strictly need keeps - but the kotlinx.serialization
# plumbing does, and any enum whose *name* we persist must survive shrinking.
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class **.Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Travel/maneuver enums are persisted in nav state + prefs by name.
-keepnames enum app.vela.core.model.** { *; }

# Rhino (runs the remote transforms.js): keep the whole engine - it resolves a lot
# of its own classes reflectively, so R8 stripping/renaming breaks it at runtime.
# It also references optional java.* desktop classes absent on Android; silence those
# warnings rather than fail the build.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# GraphHopper (on-device routing/map-matching). Keep the whole engine + its runtime deps -
# it resolves encoded values + weightings reflectively and R8 renaming breaks load/route. It
# also references OSM-import-only deps we deliberately exclude (osmosis/protobuf/woodstox/AWT)
# plus the Janino compiler we never invoke (we override the WeightingFactory) - silence those
# dangling refs rather than fail the build.
-keep class com.graphhopper.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-keep class org.locationtech.jts.** { *; }
# GraphHopper parses car.json + graph config via Jackson (reflective) - keep it so the release
# runtime path is identical to the debug build the :ghprobe on-device test already proved.
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.graphhopper.**
-dontwarn org.locationtech.jts.**
-dontwarn org.codehaus.janino.**
-dontwarn org.codehaus.commons.**
-dontwarn org.openstreetmap.osmosis.**
-dontwarn com.google.protobuf.**
-dontwarn com.fasterxml.jackson.dataformat.xml.**
-dontwarn com.ctc.wstx.**
-dontwarn org.codehaus.stax2.**
-dontwarn org.apache.xmlgraphics.**
-dontwarn java.awt.**
-dontwarn javax.xml.stream.**
-dontwarn javax.measure.**
