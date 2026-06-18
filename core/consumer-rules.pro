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
