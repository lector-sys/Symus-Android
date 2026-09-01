# NewPipeExtractor runs YouTube's own player JavaScript through Rhino to
# deobfuscate stream URLs. Rhino resolves classes reflectively at runtime, so R8
# sees no references to them and strips the engine — the app then builds, starts,
# and fails to play anything with a NoClassDefFoundError deep inside extraction.
# These keeps are the difference between a working release APK and a silent one.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**

# Rhino optionally uses these; they are absent by design, and the warnings are
# not actionable.
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**

# The extractor deserialises YouTube's JSON into these types by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# nanojson, used by the extractor for parsing, reflects over its own writers.
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# Kotlin coroutines' internal service loader entries.
-dontwarn kotlinx.coroutines.**
