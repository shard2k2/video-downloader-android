# =============================================================
# VidTown ProGuard Rules
# =============================================================
# These rules tell the minifier what NOT to touch.
# If a class or method is listed here, it won't be renamed
# or removed even if it looks "unused" to the analyser.
# =============================================================

# yt-dlp android library — keep everything in these packages
# because they use reflection internally and renaming breaks them
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# FFmpeg android library
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# Kotlin coroutines — needed for background download work
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlin serialization (used internally by some AndroidX libraries)
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep our own data classes intact so history JSON parsing works.
# If DownloadRecord gets renamed by ProGuard, fromJson/toJson break.
-keep class com.example.ytmpe.DownloadRecord { *; }
-keep class com.example.ytmpe.DownloadJob { *; }

# AndroidX FileProvider — needed for tap-to-open-file notifications
-keep class androidx.core.content.FileProvider { *; }

# Keep enum classes intact (used in yt-dlp update status checks)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress warnings about missing classes in libraries we don't use
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**