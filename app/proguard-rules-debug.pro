# Debug-variant-only R8 rules (release stays obfuscated via proguard-rules.pro).
# The debug build runs R8 for release-grade smoothness while staying `debuggable`; keep class and
# method names + line tables so Timber, crash reports, and ANR/ExitInfo stacks are human-readable
# without a mapping-file retrace. Names live in the dex string pool - zero runtime cost.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
