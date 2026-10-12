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

# WorkManager, which is how Glance gets a widget its content.
#
# WorkManager instantiates these by class name and calls the no-argument
# constructor. R8 sees no caller, removes the constructor, and the failure is
# not a crash but a widget that never fills in:
#
#   WM-InputMerger: Trouble instantiating androidx.work.OverwritingInputMerger
#   java.lang.InstantiationException: ... has no zero argument constructor
#   WM-WorkerWrapper: Could not create Input Merger
#
# The update work then never runs, so both home-screen widgets sat on Glance's
# loading placeholder — two blank white rectangles — in every release build,
# while debug was fine. Constructors only; the classes themselves may still be
# shrunk and renamed.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# MediaPipe's GenAI task, which is what runs the on-device model.
#
# Two separate problems. AutoValue is a compile-time annotation processor:
# the annotations are referenced by the generated options classes but the
# annotation types themselves are not on the runtime classpath, so R8 reports
# them missing and refuses to finish. They genuinely are not needed at runtime.
-dontwarn com.google.auto.value.**

# The rest is JNI. The native inference engine looks its Java peers up by name
# through the JNI bridge, so R8 sees no reference to them and is free to rename
# or remove them — and the failure is an UnsatisfiedLinkError at model load,
# which only ever shows up in a minified build.
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.proto.** { *; }
-dontwarn com.google.mediapipe.**
