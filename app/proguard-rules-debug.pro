# Debug-variant-only R8 rules (release stays obfuscated via proguard-rules.pro).
# The debug build runs R8 for release-grade smoothness while staying `debuggable`; keep class and
# method names + line tables so Timber, crash reports, and ANR/ExitInfo stacks are human-readable
# without a mapping-file retrace. Names live in the dex string pool - zero runtime cost.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Instrumented self-coverage suite (app/src/androidTest): the test APK resolves the Kotlin stdlib
# from the APP's dex at runtime, but R8's shrinker removes the parts the app itself doesn't call
# (kotlin.LazyKt was the first casualty). Keep the stdlib wholesale in DEBUG only - the diagnosis
# build carries a little extra weight so the tour can run against the same minified binary class.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
# Same story for the coroutines runtime: the Compose test framework calls members the app never
# does (CompletableJob.complete was the second casualty).
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
