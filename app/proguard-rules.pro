# PlayTube — R8 rules for the shrunk/obfuscated build.
#
# The goal here is to make a decompiled release APK hard to read and hard to
# patch (class/method/field names are stripped and renamed, unused code is
# removed), WITHOUT breaking anything that finds classes/methods by name at
# runtime (reflection). Several vendored/third-party libraries do exactly
# that, so they are explicitly told to keep their real names and every
# member — R8 is still free to shrink/obfuscate our own app code around them.
#
# If a future release crashes only in a minified build (works fine via
# compactTest, which never shrinks), the fix is almost always one more
# -keep rule here for whatever class the stack trace names, not turning
# minification back off.

# --- NewPipeExtractor (vendored, patched jar) + its runtime deps ---
# Heavy use of reflection/dynamic dispatch across extractors and parsers.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (JS engine NewPipeExtractor uses to run YouTube's player JS)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# jsoup (HTML parsing)
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# nanojson (JSON parsing used by the extractor)
-keep class com.github.teamnewpipe.nanojson.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# protobuf-javalite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- Media3 / ExoPlayer: some extractors/renderers are loaded by name ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- OkHttp / okio ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Our own data/model classes kept whole (Parcelable CREATOR fields,
#     anything (de)serialized to local JSON/backup files by field name) ---
-keep class com.play.tube.data.** { *; }
-keep class com.play.tube.playback.SponsorBlock { *; }
-keep class com.play.tube.util.UpdateChecker { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Standard Kotlin/enum safety nets
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep line numbers in stack traces for our own local crash log reader,
# but drop the source file name (SourceFile+LineNumberTable instead).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
