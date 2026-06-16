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
