package com.example.ytmpe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class DownloadJob(val url: String, val format: String, val queuedId: String, val startId: Int)

class DownloadService : Service() {

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    // Keeps the CPU alive during downloads so long videos don't fail when the screen turns off.
    // Without this, Samsung One UI 6 (A25) throttles the CPU after ~1-2 min, killing connections.
    private var wakeLock: PowerManager.WakeLock? = null
    private val queue = ConcurrentLinkedQueue<DownloadJob>()
    @Volatile private var isProcessing = false
    @Volatile private var isCancelled  = false

    @Volatile private var activeProcessId:     String?      = null
    @Volatile private var activeJob:           DownloadJob? = null
    @Volatile private var activeResolvedTitle: String?      = null

    companion object {
        private val notifCounter = AtomicInteger(100) // starts at 100 to avoid clash with NOTIF_ID_PROGRESS=1
        const val CHANNEL_PROGRESS        = "dl_progress"
        const val CHANNEL_COMPLETE        = "dl_complete"
        const val NOTIF_ID_PROGRESS       = 1
        const val EXTRA_URL               = "url"
        const val EXTRA_FORMAT            = "format"
        const val FILE_PROVIDER_AUTHORITY = "com.example.ytmpe.fileprovider"
        const val ACTION_CANCEL             = "com.example.ytmpe.CANCEL_DOWNLOAD"
        const val EXTRA_CANCEL_PROCESS_ID   = "cancelProcessId"

        // ── Progress broadcast ────────────────────────────────────
        // The service sends these broadcasts so MainActivity can show
        // live progress without binding to the service. Broadcasts are
        // Android's lightweight pub/sub — no direct reference needed.
        const val ACTION_PROGRESS        = "com.example.ytmpe.DOWNLOAD_PROGRESS"
        const val EXTRA_PROCESS_ID       = "processId"
        const val EXTRA_TITLE            = "title"
        const val EXTRA_PERCENT          = "percent"
        const val EXTRA_STATUS_TEXT      = "statusText"
        // STATUS values sent in the broadcast
        const val STATUS_ACTIVE          = "active"    // downloading right now
        const val STATUS_QUEUED          = "queued"    // waiting in queue
        const val STATUS_DONE            = "done"      // finished successfully
        const val STATUS_FAILED          = "failed"    // error
        const val STATUS_CANCELLED       = "cancelled" // user cancelled
        const val STATUS_REMOVE          = "remove"    // tells UI to remove this entry from the map
        const val EXTRA_STATUS           = "status"
        const val EXTRA_FILE_PATH        = "filePath"
    }

    // -----------------------------------------------------------------
    // Cancel receiver
    // -----------------------------------------------------------------
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CANCEL) return
            val targetId = intent.getStringExtra(EXTRA_CANCEL_PROCESS_ID) ?: ""
            // Cancel only if no specific ID was requested, or it matches the active download.
            if (targetId.isBlank() || targetId == activeProcessId) cancelCurrentDownload()
        }
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Clean up leftover .part/.ytdl files from a previous crashed session.
        // Done here — once, at service start — so it never runs while a download
        // is active (including yt-dlp's post-processing which creates its own .part files).
        deletePartialFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ (API 34+) requires startForeground() to be called within 5 seconds of
        // startForegroundService(). Call it unconditionally and immediately — before any other
        // work — so we never miss that window even if the calling activity has already finished.
        // Calling startForeground() multiple times is safe; it just updates the notification.
        startForeground(
            NOTIF_ID_PROGRESS,
            buildProgressNotification("VidTown", "Preparing...", 0f, true, showCancel = false)
        )

        val url    = intent?.getStringExtra(EXTRA_URL)   ?: return START_NOT_STICKY
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "MP4_1080"

        isCancelled = false

        val queuedId = "queued_${System.currentTimeMillis()}"
        val newJob = DownloadJob(url, format, queuedId, startId)
        queue.add(newJob)

        // Broadcast queued status immediately so UI shows the item
        broadcastProgress(
            processId  = queuedId,
            title      = extractSiteName(url),
            percent    = 0f,
            statusText = "Queued",
            status     = STATUS_QUEUED,
            filePath   = ""
        )

        if (!isProcessing) processQueue()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cancelReceiver)
        releaseWakeLock()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------

    private fun cancelCurrentDownload() {
        isCancelled = true

        val killedId = activeProcessId
        activeProcessId?.let { cancelDownload(it) }
        activeProcessId = null

        deletePartialFiles()

        val job = activeJob
        if (job != null) {
            val title = activeResolvedTitle?.takeIf { it != extractSiteName(job.url) }
                ?: "Cancelled download"
            val now = System.currentTimeMillis()

            // Tell the UI immediately (main thread — fast, no IO)
            broadcastProgress(
                processId  = killedId ?: "",
                title      = title,
                percent    = 0f,
                statusText = "Cancelled",
                status     = STATUS_CANCELLED,
                filePath   = ""
            )

            // Persist to history on IO thread — avoids blocking the main thread
            val record = DownloadRecord(
                id        = now,
                title     = title,
                url       = job.url,
                format    = job.format,
                filePath  = "",
                timestamp = now,
                success   = false,
                status    = "cancelled"
            )
            serviceScope.launch {
                DownloadHistory.save(this@DownloadService, record)
            }
        }

        queue.clear()
        isProcessing = false
        releaseWakeLock()

        notificationManager.notify(
            NOTIF_ID_PROGRESS,
            buildProgressNotification("VidTown", "Download cancelled.", 0f, false, showCancel = false)
        )

        stopSelf()
    }

    // -----------------------------------------------------------------
    // Queue processor
    // -----------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmpe:download")
        wakeLock?.acquire(60 * 60 * 1000L) // max 1 hour; released earlier when queue empties
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun processQueue() {
        if (isCancelled || queue.isEmpty()) {
            isProcessing = false
            releaseWakeLock()
            return
        }

        acquireWakeLock()
        isProcessing = true
        val job       = queue.poll() ?: return
        val timestamp = System.currentTimeMillis()
        val processId = "dl_$timestamp"
        val label     = formatLabel(job.format)
        val queueSize = queue.size
        val queueNote = if (queueSize > 0) " · +$queueSize queued" else ""

        activeProcessId     = processId
        activeJob           = job
        activeResolvedTitle = null

        serviceScope.launch {
            // Remove the exact "queued" placeholder for this job
            broadcastProgress(job.queuedId, "", 0f, "", STATUS_REMOVE, "")

            val siteName = extractSiteName(job.url)
            var resolvedTitle = siteName

            broadcastProgress(processId, resolvedTitle, 0f, "$label · Starting...", STATUS_ACTIVE, "")
            updateNotification(resolvedTitle, "$label · Starting...$queueNote", 0f, indeterminate = true, showCancel = true, processId = processId)

            val filePath = downloadVideo(
                context    = this@DownloadService,
                url        = job.url,
                format     = job.format,
                processId  = processId,
                onProgress = { percent, line ->
                    if (isCancelled) return@downloadVideo

                    // Parse title from Destination line
                    if (resolvedTitle == siteName
                        && line.contains("[download]", ignoreCase = true)
                        && line.contains("Destination:", ignoreCase = true)
                    ) {
                        val filename = line
                            .substringAfter("Destination:").trim()
                            .substringAfterLast("/")
                            .substringBeforeLast(".").trim()
                        if (filename.length > 3) {
                            resolvedTitle       = filename.take(60)
                            activeResolvedTitle = resolvedTitle
                        }
                    }

                    val statusText = when {
                        percent < 0f   -> line
                        percent == 0f  -> "$label · Connecting...$queueNote"
                        percent < 99f  -> "$label · ${percent.toInt()}%$queueNote"
                        else           -> "$label · Merging...$queueNote"
                    }
                    val showCancel = percent in 0f..98f

                    broadcastProgress(processId, resolvedTitle, percent.coerceAtLeast(0f), statusText, STATUS_ACTIVE, "")
                    updateNotification(resolvedTitle, statusText, percent.coerceAtLeast(0f),
                        indeterminate = percent == 0f, showCancel = showCancel, processId = processId)
                }
            )

            if (isCancelled) {
                isProcessing = false
                return@launch
            }

            if (filePath == ALREADY_DOWNLOADED) {
                // yt-dlp skipped — file already exists. Not a failure, don't record to history.
                broadcastProgress(processId, resolvedTitle, 100f, "Already in Downloads", STATUS_DONE, "")
                if (!isCancelled) processQueue()
                stopSelf(job.startId)
                return@launch
            }

            val finalTitle = if (resolvedTitle != siteName) resolvedTitle
            else fetchVideoTitle(this@DownloadService, job.url)

            val success = filePath != null
            DownloadHistory.save(
                context = this@DownloadService,
                record  = DownloadRecord(
                    id        = timestamp,
                    title     = finalTitle,
                    url       = job.url,
                    format    = job.format,
                    filePath  = filePath ?: "",
                    timestamp = System.currentTimeMillis(),
                    success   = success,
                    status    = if (success) "success" else "failed"
                )
            )

            if (success) {
                broadcastProgress(processId, finalTitle, 100f, "Done!", STATUS_DONE, filePath!!)
                showCompletionNotification(finalTitle, label, filePath)
            } else {
                broadcastProgress(processId, finalTitle, 0f, "Failed", STATUS_FAILED, "")
                showFailureNotification(finalTitle, label)
            }

            if (!isCancelled) processQueue()
            // stopSelf with the job's own startId: Android only actually stops the service
            // if no newer startForegroundService call has been delivered since this job was
            // queued. This prevents the service from dying while subsequent downloads are
            // still waiting in onStartCommand — the root cause of rapid-queue failures.
            stopSelf(job.startId)
        }
    }

    // -----------------------------------------------------------------
    // Broadcast helper — sends progress to MainActivity
    // -----------------------------------------------------------------
    private fun broadcastProgress(
        processId:  String,
        title:      String,
        percent:    Float,
        statusText: String,
        status:     String,
        filePath:   String
    ) {
        val intent = Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROCESS_ID,  processId)
            putExtra(EXTRA_TITLE,       title)
            putExtra(EXTRA_PERCENT,     percent)
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_STATUS,      status)
            putExtra(EXTRA_FILE_PATH,   filePath)
        }
        sendBroadcast(intent)
    }

    // -----------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------

    private fun updateNotification(title: String, text: String, percent: Float,
                                   indeterminate: Boolean, showCancel: Boolean,
                                   processId: String = "") {
        notificationManager.notify(NOTIF_ID_PROGRESS,
            buildProgressNotification(title, text, percent, indeterminate, showCancel, processId))
    }

    private fun buildProgressNotification(
        title: String, text: String, percent: Float,
        indeterminate: Boolean, showCancel: Boolean,
        processId: String = ""
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent.toInt(), indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        if (showCancel) {
            val pi = PendingIntent.getBroadcast(
                this, processId.hashCode(),
                Intent(ACTION_CANCEL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CANCEL_PROCESS_ID, processId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_delete, "Cancel", pi)
        }

        return builder.build()
    }

    private fun showCompletionNotification(title: String, label: String, filePath: String) {
        NotificationCompat.Builder(this, CHANNEL_COMPLETE)
            .setContentTitle("Download complete")
            .setContentText("$title · $label")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(buildOpenFileIntent(filePath))
            .build()
            .also { notificationManager.notify(notifCounter.incrementAndGet(), it) }
    }

    private fun showFailureNotification(title: String, label: String) {
        NotificationCompat.Builder(this, CHANNEL_COMPLETE)
            .setContentTitle("Download failed")
            .setContentText("$title · $label")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
            .also { notificationManager.notify(notifCounter.incrementAndGet(), it) }
    }

    private fun buildOpenFileIntent(filePath: String): PendingIntent {
        val file = File(filePath)
        val mime = when (file.extension.lowercase()) {
            "mp3", "m4a", "opus", "ogg" -> "audio/*"
            "mp4", "mkv", "webm"        -> "video/*"
            else                         -> "*/*"
        }
        val uri = try {
            FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
        } catch (_: Exception) { Uri.fromFile(file) }

        return PendingIntent.getActivity(
            this, filePath.hashCode(),
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannels() {
        NotificationChannel(CHANNEL_PROGRESS, "Download Progress", NotificationManager.IMPORTANCE_LOW)
            .also { notificationManager.createNotificationChannel(it) }
        NotificationChannel(CHANNEL_COMPLETE, "Download Complete", NotificationManager.IMPORTANCE_DEFAULT)
            .also { notificationManager.createNotificationChannel(it) }
    }

    private fun deletePartialFiles() {
        try {
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val files = dir.listFiles() ?: return

            // yt-dlp creates paired sidecar files: "Video.mp4.part" + "Video.mp4.ytdl".
            // Only delete .part files that have a matching .ytdl file — those belong to yt-dlp.
            // Leave .part files from other apps (browsers, etc.) untouched.
            val ytdlBasenames = files
                .filter { it.name.endsWith(".ytdl") }
                .map { it.name.removeSuffix(".ytdl") }
                .toSet()

            files.forEach { file ->
                when {
                    file.name.endsWith(".ytdl") -> file.delete()
                    file.name.endsWith(".part")
                        && file.name.removeSuffix(".part") in ytdlBasenames -> file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractSiteName(url: String): String {
        val d = url.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").substringBefore("/").substringBefore("?").lowercase()
        return when {
            d.contains("youtube") || d.contains("youtu.be") -> "YouTube"
            d.contains("facebook") || d.contains("fb.watch") -> "Facebook"
            d.contains("instagram") -> "Instagram"
            d.contains("twitter") || d.contains("x.com") -> "X / Twitter"
            d.contains("tiktok") -> "TikTok"
            d.contains("vimeo") -> "Vimeo"
            else -> d.replaceFirstChar { it.uppercase() }
        }
    }
}