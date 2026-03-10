package com.example.ytmpe

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------
// ensureInitialized()
// -----------------------------------------------------------------
private var isInitialized = false

fun ensureInitialized(context: Context) {
    if (!isInitialized) {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        isInitialized = true
    }
}

// -----------------------------------------------------------------
// updateYtDlp() — once per day only
// -----------------------------------------------------------------
private const val PREFS_UPDATE    = "vidtown_update"
private const val KEY_LAST_UPDATE = "last_update_ms"
private const val ONE_DAY_MS      = 24 * 60 * 60 * 1000L

suspend fun updateYtDlp(context: Context): String {
    return withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val prefs      = context.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
            val now        = System.currentTimeMillis()
            if (now - lastUpdate < ONE_DAY_MS) {
                return@withContext "yt-dlp update skipped (checked recently)"
            }
            val result = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
            when (result) {
                YoutubeDL.UpdateStatus.DONE                -> "yt-dlp updated successfully"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp already up to date"
                else                                       -> "Update status unknown"
            }
        } catch (e: Exception) {
            "Update failed: ${e.message}"
        }
    }
}

// -----------------------------------------------------------------
// fetchVideoTitle()
// -----------------------------------------------------------------
suspend fun fetchVideoTitle(context: Context, url: String): String {
    return withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val request = YoutubeDLRequest(url)
            request.addOption("--get-title")
            request.addOption("--no-playlist")
            val response = YoutubeDL.getInstance().execute(request)
            response.out.trim().lines().firstOrNull()?.take(80) ?: "Unknown Title"
        } catch (_: Exception) {
            "Unknown Title"
        }
    }
}

// -----------------------------------------------------------------
// downloadVideo()
// -----------------------------------------------------------------
// processId: a unique string we give this download so we can cancel
// it later using YoutubeDL.getInstance().destroyProcessById(id).
// Pass any unique string — we use the job timestamp from the service.
// -----------------------------------------------------------------
suspend fun downloadVideo(
    context: Context,
    url: String,
    format: String,
    processId: String,
    onProgress: (Float, String) -> Unit
): String? {
    return withContext(Dispatchers.IO) {

        ensureInitialized(context)
        onProgress(0f, "Starting download...")

        val outputDir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        val beforeFiles = outputDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val request = YoutubeDLRequest(url)
        request.addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
        request.addOption("--extractor-retries", "3")
        request.addOption("--no-check-certificates")
        request.addOption("--no-playlist")

        when (format) {
            "MP3" -> {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
                request.addOption("--embed-thumbnail")
                request.addOption("--convert-thumbnails", "jpg")
                request.addOption("--embed-metadata")
                request.addOption("--parse-metadata", "%(title)s:%(meta_title)s")
                request.addOption("--parse-metadata", "%(uploader)s:%(meta_artist)s")
            }
            "MP4_360" -> {
                request.addOption("-f", "bestvideo[height<=480]+bestaudio/best[height<=480]")
                request.addOption("--merge-output-format", "mp4")
            }
            else -> {
                request.addOption("-f", "bestvideo[height<=1080]+bestaudio/best")
                request.addOption("--merge-output-format", "mp4")
            }
        }

        try {
            // processId lets us cancel this specific download later.
            // The library uses it to track the underlying yt-dlp process.
            YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                onProgress(progress, line)
            }

            val afterFiles = outputDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
            val newFile = (afterFiles - beforeFiles).firstOrNull()

            MediaScannerConnection.scanFile(
                context,
                arrayOf(newFile ?: outputDir.absolutePath),
                null, null
            )

            onProgress(100f, "Done! Saved to Downloads.")
            newFile

        } catch (e: Exception) {
            // Convert raw yt-dlp error text into friendly messages.
            // e.message contains the raw exception text which is often
            // very technical — we match keywords to give a clear message.
            val friendly = friendlyError(e.message ?: "")
            onProgress(-1f, friendly)
            null
        }
    }
}

// -----------------------------------------------------------------
// friendlyError()
// -----------------------------------------------------------------
// -----------------------------------------------------------------
fun friendlyError(raw: String): String {
    val msg = raw.lowercase()
    return when {
        // User cancelled — not really an error
        msg.contains("cancelled") || msg.contains("interrupted") ->
            "Download cancelled."

        // Video is private or requires login
        msg.contains("private") ->
            "This video is private and cannot be downloaded."

        msg.contains("login") || msg.contains("sign in") || msg.contains("authenticate") ->
            "This video requires a login to download."

        // Video not available in this region
        msg.contains("not available") || msg.contains("unavailable") ->
            "This video is not available in your region."

        // Video has been removed
        msg.contains("removed") || msg.contains("deleted") || msg.contains("no longer") ->
            "This video has been removed or deleted."

        // Age restricted
        msg.contains("age") && (msg.contains("restrict") || msg.contains("limit")) ->
            "This video is age-restricted and cannot be downloaded."

        // Copyright blocked
        msg.contains("copyright") || msg.contains("blocked") ->
            "This video is blocked due to copyright restrictions."

        // Network / connection issues
        msg.contains("network") || msg.contains("connection") || msg.contains("timeout") ||
                msg.contains("unable to connect") ->
            "Connection failed. Check your internet and try again."

        // Invalid URL
        msg.contains("unsupported url") || msg.contains("is not a valid url") ||
                msg.contains("no video formats") ->
            "This URL is not supported. Try a YouTube or Facebook link."

        // Playlist without --no-playlist (shouldn't happen but just in case)
        msg.contains("playlist") ->
            "Playlists are not supported. Please share a single video."

        // Generic fallback — still better than the raw error
        else -> "Download failed. The video may be unavailable or the URL is invalid."
    }
}

// -----------------------------------------------------------------
// cancelDownload()
// -----------------------------------------------------------------
// Kills the yt-dlp process with the given ID immediately.
// Called by DownloadService when the user taps Cancel.
// -----------------------------------------------------------------
fun cancelDownload(processId: String) {
    try {
        YoutubeDL.getInstance().destroyProcessById(processId)
    } catch (_: Exception) {
        // Process may have already finished — safe to ignore
    }
}

// -----------------------------------------------------------------
// formatLabel()
// -----------------------------------------------------------------
fun formatLabel(format: String): String = when (format) {
    "MP3"      -> "MP3 Audio"
    "MP4_360"  -> "MP4 Low Quality"
    "MP4_1080" -> "MP4 1080p"
    else       -> "MP4 1080p"
}