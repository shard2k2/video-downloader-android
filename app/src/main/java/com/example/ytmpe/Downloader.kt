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
@Volatile private var isInitialized = false

@Synchronized
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

// Sentinel returned by downloadVideo() when yt-dlp skipped the file
// because it already exists (--no-overwrites). Not an error.
const val ALREADY_DOWNLOADED = "ALREADY_DOWNLOADED"

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
        // Retry on network drops — critical for long videos on weaker devices
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        // Abort stalled connections instead of hanging forever
        request.addOption("--socket-timeout", "30")
        // Skip if the output file already exists — prevents yt-dlp from re-downloading
        // fragments and then hanging trying to merge into an already-existing mp4.
        request.addOption("--no-overwrites")

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
                // Prefer a pre-muxed MP4 (no FFmpeg merge needed) — avoids OOM on
                // low-RAM devices (e.g. Samsung A23) when processing long videos.
                // Falls back to DASH merge only if no pre-muxed stream exists.
                request.addOption("-f", "best[height<=360][ext=mp4]/bestvideo[height<=360]+bestaudio/best[height<=360]")
                request.addOption("--merge-output-format", "mp4")
            }
            else -> {
                // Prefer mp4 video + m4a audio so FFmpeg only remuxes (fast, seconds)
                // instead of transcoding webm→mp4 (slow, can appear stuck for minutes).
                // Falls back to any video+audio merge, then to best single-file format.
                request.addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best")
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
            val newFiles = (afterFiles - beforeFiles).filter { path ->
                !path.endsWith(".part") && !path.endsWith(".ytdl") && !path.endsWith(".temp")
            }
            val expectedExt = if (format == "MP3") ".mp3" else ".mp4"
            val newFile = newFiles.firstOrNull { it.endsWith(expectedExt) } ?: newFiles.firstOrNull()

            if (newFile == null) {
                // No new file was created — yt-dlp skipped because the file already exists.
                onProgress(-1f, "already_downloaded")
                return@withContext ALREADY_DOWNLOADED
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(newFile),
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
        // File already exists — yt-dlp skipped it
        msg.contains("already_downloaded") ->
            "Already downloaded. Check your Downloads folder."

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