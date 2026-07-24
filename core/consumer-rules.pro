# Consumer R8 rules contributed by :core to whatever app depends on it.
#
# We parse Google's responses positionally (no reflective field names), so the
# model classes don't strictly need keeps — but the kotlinx.serialization
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

# Rhino (runs the remote transforms.js): keep the whole engine — it resolves a lot
# of its own classes reflectively, so R8 stripping/renaming breaks it at runtime.
# It also references optional java.* desktop classes absent on Android; silence those
# warnings rather than fail the build.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# GraphHopper (on-device routing/map-matching). Keep the whole engine + its runtime deps —
# it resolves encoded values + weightings reflectively and R8 renaming breaks load/route. It
# also references OSM-import-only deps we deliberately exclude (osmosis/protobuf/woodstox/AWT)
# plus the Janino compiler we never invoke (we override the WeightingFactory) — silence those
# dangling refs rather than fail the build.
-keep class com.graphhopper.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-keep class org.locationtech.jts.** { *; }
# GraphHopper parses car.json + graph config via Jackson (reflective) — keep it so the release
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

# OsmAnd obf router (ObfRouteEngine): parses routing.xml/poi_types.xml resources by classloader and
# wires GeneralRouter parameters reflectively enough that shrinking is not worth the risk - keep it
# wholesale like GraphHopper. The desktop-only corners (awt image io in some utilities) never run on
# Android; silence, don't fail.
-keep class net.osmand.** { *; }
-dontwarn net.osmand.**
-keep class gnu.trove.** { *; }
# commons-logging picks its LogFactory IMPLEMENTATION by reflective discovery, so R8 sees no
# reference and strips LogFactoryImpl - then BinaryMapIndexReader's static init (PlatformUtil.getLog)
# throws ClassNotFoundException and every obf open fails (release-canary find, 2026-07-23).
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.commons.logging.**
# kxml2's parser is instantiated reflectively through the XmlPullParserFactory lookup - same
# no-static-reference shape as LogFactoryImpl above.
-keep class org.kxml2.** { *; }
-dontwarn org.kxml2.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.stream.**
# Desktop-only corners of osmand-java that never run on Android (NMEA sentence parsing, the
# wdtinc MVT reader's legacy-JTS path, kotlin-native concurrency shims) - silence, don't fail.
-dontwarn co.touchlab.stately.**
-dontwarn com.vividsolutions.jts.**
-dontwarn com.wdtinc.**
-dontwarn net.sf.marineapi.**
