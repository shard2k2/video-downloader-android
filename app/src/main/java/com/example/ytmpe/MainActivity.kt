package com.example.ytmpe

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────
// Design tokens — Samsung One UI inspired
// ─────────────────────────────────────────────────────────────────
val SBg        = Color(0xFF0D0D14)
val SCard      = Color(0xFF1A1A24)
val SCardAlt   = Color(0xFF22222F)
val SAccent    = Color(0xFF00C4AA)
val SAccentDim = Color(0xFF00C4AA).copy(alpha = 0.12f)
val SText      = Color(0xFFEEEEF5)
val STextSub   = Color(0xFF8888A8)
val SError     = Color(0xFFFF4D6A)
val SDivider   = Color(0xFF252535)
val SSuccess   = Color(0xFF34C759)
val SAmber     = Color(0xFFFF9800)

// ─────────────────────────────────────────────────────────────────
// Data class for live download state shown in the UI
// ─────────────────────────────────────────────────────────────────
data class ActiveDownload(
    val processId:  String,
    val title:      String,
    val percent:    Float,
    val statusText: String,
    val status:     String,  // active | queued | done | failed | cancelled
    val filePath:   String
)

// ─────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    // Mutable state updated by the progress receiver — hoisted here
    // so it survives recomposition and can be shared with any composable
    private val activeDownloads = mutableStateMapOf<String, ActiveDownload>()

    // Receives ACTION_PROGRESS broadcasts from DownloadService
    // and updates the activeDownloads map
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadService.ACTION_PROGRESS) return
            val processId  = intent.getStringExtra(DownloadService.EXTRA_PROCESS_ID)  ?: return
            val title      = intent.getStringExtra(DownloadService.EXTRA_TITLE)       ?: ""
            val percent    = intent.getFloatExtra(DownloadService.EXTRA_PERCENT, 0f)
            val statusText = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
            val status     = intent.getStringExtra(DownloadService.EXTRA_STATUS)      ?: ""
            val filePath   = intent.getStringExtra(DownloadService.EXTRA_FILE_PATH)   ?: ""

            activeDownloads[processId] = ActiveDownload(
                processId, title, percent, statusText, status, filePath
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                MainScreen(activeDownloads = activeDownloads)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ensureInitialized(this@MainActivity)
            updateYtDlp(this@MainActivity)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            progressReceiver,
            IntentFilter(DownloadService.ACTION_PROGRESS),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        // Unregister when app goes to background — notifications handle it there
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────────────
// App theme
// ─────────────────────────────────────────────────────────────────
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background   = SBg,
            surface      = SCard,
            primary      = SAccent,
            onBackground = SText,
            onSurface    = SText,
        ),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────
// MainScreen — two tabs with swipe support
// ─────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(activeDownloads: Map<String, ActiveDownload>) {
    val context     = LocalContext.current
    val pagerState  = rememberPagerState(pageCount = { 2 })
    val scope       = rememberCoroutineScope()

    var historyItems by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }

    // Reload history whenever:
    // 1. User swipes to the Downloads tab
    // 2. Any active download finishes/fails/cancels
    // 3. App comes back from background (share flow) — handled in onResume
    val finishedStatuses = setOf(
        DownloadService.STATUS_DONE,
        DownloadService.STATUS_FAILED,
        DownloadService.STATUS_CANCELLED
    )

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            historyItems = DownloadHistory.loadAll(context)
        }
    }

    LaunchedEffect(activeDownloads.values.map { it.status }) {
        if (activeDownloads.values.any { it.status in finishedStatuses }) {
            historyItems = DownloadHistory.loadAll(context)
        }
    }

    // Also reload history on first composition — catches downloads started
    // via share while app was closed (they're saved to DB by the service)
    LaunchedEffect(Unit) {
        historyItems = DownloadHistory.loadAll(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SBg)
            .systemBarsPadding()
    ) {
        AppBar()

        // Tab row stays fixed at top, pager scrolls below
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor   = SBg,
            contentColor     = SAccent,
            indicator = { tabPositions ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = tabPositions[pagerState.currentPage].left)
                        .width(tabPositions[pagerState.currentPage].width)
                        .padding(horizontal = 32.dp)
                        .height(2.dp)
                        .background(SAccent, RoundedCornerShape(50))
                )
            },
            divider = {
                Box(Modifier.fillMaxWidth().height(1.dp).background(SDivider))
            }
        ) {
            SamsungTab("Download",  pagerState.currentPage == 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            SamsungTab("Downloads", pagerState.currentPage == 1) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        }

        // HorizontalPager enables swipe — pageCount=2, one page per tab
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> DownloadTab(onDownloadQueued = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                })
                1 -> DownloadsTab(
                    activeDownloads = activeDownloads,
                    historyItems    = historyItems,
                    onDelete = { record ->
                        DownloadHistory.delete(context, record.id)
                        historyItems = DownloadHistory.loadAll(context)
                    },
                    onClearAll = {
                        DownloadHistory.clearAll(context)
                        historyItems = emptyList()
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// App bar
// ─────────────────────────────────────────────────────────────────
@Composable
fun AppBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SAccentDim)
                .border(1.dp, SAccent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("▼", color = SAccent, fontSize = 14.sp) }

        Spacer(Modifier.width(12.dp))

        Column {
            Text("VidTown", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SText)
            Text("Video & audio downloader", fontSize = 12.sp, color = STextSub)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Tab button
// ─────────────────────────────────────────────────────────────────
@Composable
fun SamsungTab(title: String, selected: Boolean, onClick: () -> Unit) {
    val textColor by animateColorAsState(
        if (selected) SAccent else STextSub, tween(200), label = "tab"
    )
    Tab(selected = selected, onClick = onClick,
        selectedContentColor = SAccent, unselectedContentColor = STextSub) {
        Text(
            text       = title,
            color      = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize   = 14.sp,
            modifier   = Modifier.padding(vertical = 12.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Download tab — URL input + format + queue button
// ─────────────────────────────────────────────────────────────────
@Composable
fun DownloadTab(onDownloadQueued: () -> Unit) {
    val context = LocalContext.current

    var url            by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("MP4_1080") }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard {
                Text("Video URL", fontSize = 13.sp, color = STextSub, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    placeholder   = {
                        Text("Paste a YouTube or Facebook URL", color = STextSub, fontSize = 14.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = SText,
                        unfocusedTextColor   = SText,
                        focusedBorderColor   = SAccent,
                        unfocusedBorderColor = SDivider,
                        cursorColor          = SAccent
                    ),
                    shape     = RoundedCornerShape(12.dp),
                    modifier  = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (url.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tip: share from YouTube or Facebook app directly",
                        fontSize = 12.sp, color = STextSub.copy(alpha = 0.7f)
                    )
                }
            }
        }

        item {
            SectionCard {
                Text("Format", fontSize = 13.sp, color = STextSub, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        Triple("MP3",      "🎵", "Audio"),
                        Triple("MP4_360",  "📹", "Low"),
                        Triple("MP4_1080", "🎬", "1080p"),
                    ).forEach { (code, icon, label) ->
                        FormatChip(icon, label, selectedFormat == code, Modifier.weight(1f)) {
                            selectedFormat = code
                        }
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (url.isNotBlank()) SAccent else SCard)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        enabled           = url.isNotBlank()
                    ) {
                        context.startForegroundService(
                            Intent(context, DownloadService::class.java).apply {
                                putExtra(DownloadService.EXTRA_URL, url)
                                putExtra(DownloadService.EXTRA_FORMAT, selectedFormat)
                            }
                        )
                        url = ""
                        onDownloadQueued() // switch to Downloads tab
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Download",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (url.isNotBlank()) SBg else STextSub
                )
            }
        }

        item { OpenFolderButton() }
    }
}

// ─────────────────────────────────────────────────────────────────
// Downloads tab — active progress + history in one list
// ─────────────────────────────────────────────────────────────────
@Composable
fun DownloadsTab(
    activeDownloads: Map<String, ActiveDownload>,
    historyItems:    List<DownloadRecord>,
    onDelete:        (DownloadRecord) -> Unit,
    onClearAll:      () -> Unit
) {
    val context = LocalContext.current

    // Split active downloads: running/queued vs finished
    val running  = activeDownloads.values
        .filter { it.status == DownloadService.STATUS_ACTIVE || it.status == DownloadService.STATUS_QUEUED }
        .sortedBy { it.processId }
    val finished = activeDownloads.values
        .filter { it.status in setOf(DownloadService.STATUS_DONE, DownloadService.STATUS_FAILED, DownloadService.STATUS_CANCELLED) }

    // IDs that are already shown as active — exclude from history to avoid dupes
    val activeIds = activeDownloads.keys

    val historyToShow = historyItems.filter { record ->
        // Hide history records whose processId hash matches a current active download
        activeIds.none { id -> id.hashCode().toLong() == record.id }
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Active / queued downloads ─────────────────────────────
        if (running.isNotEmpty()) {
            item {
                Text(
                    "Active",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = STextSub,
                    modifier   = Modifier.padding(horizontal = 4.dp)
                )
            }
            items(running, key = { it.processId }) { dl ->
                ActiveDownloadCard(dl)
            }
        }

        // ── Recently finished (from broadcast, not yet in DB) ─────
        if (finished.isNotEmpty()) {
            item {
                Text(
                    "Just finished",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = STextSub,
                    modifier   = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            items(finished, key = { it.processId }) { dl ->
                FinishedDownloadCard(dl, context)
            }
        }

        // ── History ───────────────────────────────────────────────
        if (historyToShow.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("History", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = STextSub)
                    Text(
                        "Clear all",
                        fontSize = 12.sp,
                        color    = SError,
                        modifier = Modifier.clickable { onClearAll() }
                    )
                }
            }
            items(historyToShow, key = { it.id }) { record ->
                HistoryCard(record, onDelete = { onDelete(record) })
            }
        }

        if (running.isEmpty() && finished.isEmpty() && historyToShow.isEmpty()) {
            item {
                Box(
                    modifier         = Modifier.fillMaxWidth().padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📭", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No downloads yet", fontSize = 16.sp, color = SText, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Paste a URL in the Download tab", fontSize = 13.sp, color = STextSub)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Active download card — shows progress bar + cancel button
// ─────────────────────────────────────────────────────────────────
@Composable
fun ActiveDownloadCard(dl: ActiveDownload) {
    val context = LocalContext.current
    val isQueued = dl.status == DownloadService.STATUS_QUEUED

    // Animate the progress bar smoothly
    val animatedPercent by animateFloatAsState(
        targetValue   = dl.percent / 100f,
        animationSpec = tween(300),
        label         = "progress_${dl.processId}"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SCard)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Format icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SAccentDim),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isQueued) "⏳" else "⬇", fontSize = 16.sp)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dl.title,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color      = SText,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(dl.statusText, fontSize = 12.sp, color = STextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.width(8.dp))

            // Cancel button
            if (!isQueued) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SError.copy(alpha = 0.12f))
                        .border(1.dp, SError.copy(alpha = 0.3f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) {
                            context.sendBroadcast(
                                Intent(DownloadService.ACTION_CANCEL).apply {
                                    setPackage(context.packageName)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 12.sp, color = SError)
                }
            }
        }

        if (!isQueued) {
            Spacer(Modifier.height(12.dp))

            // Progress bar track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SCardAlt)
            ) {
                // Filled portion
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedPercent.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(SAccent)
                )
            }

            if (dl.percent > 0f) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${dl.percent.toInt()}%",
                    fontSize = 11.sp,
                    color    = SAccent
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Just-finished card (from broadcast, before history reloads)
// ─────────────────────────────────────────────────────────────────
@Composable
fun FinishedDownloadCard(dl: ActiveDownload, context: Context) {
    val isDone      = dl.status == DownloadService.STATUS_DONE
    val isCancelled = dl.status == DownloadService.STATUS_CANCELLED
    val iconBg      = when {
        isDone      -> SAccentDim
        isCancelled -> SAmber.copy(alpha = 0.15f)
        else        -> SError.copy(alpha = 0.12f)
    }
    val icon = when {
        isCancelled -> "⊘"
        !isDone     -> "✕"
        else        -> "✓"
    }
    val labelColor = when {
        isDone      -> SSuccess
        isCancelled -> SAmber
        else        -> SError
    }
    val label = when {
        isDone      -> "Complete"
        isCancelled -> "Cancelled"
        else        -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SCard)
            .clickable(enabled = isDone && dl.filePath.isNotBlank()) {
                openFile(context, dl.filePath)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 16.sp, color = labelColor) }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(dl.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 12.sp, color = labelColor)
        }

        if (isDone) {
            Text("›", fontSize = 20.sp, color = STextSub)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// History card — persistent records from DB
// ─────────────────────────────────────────────────────────────────
@Composable
fun HistoryCard(record: DownloadRecord, onDelete: () -> Unit) {
    val context     = LocalContext.current
    val isCancelled = record.status == "cancelled"
    val dateStr     = remember(record.timestamp) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    val iconBg = when {
        record.success -> SAccentDim
        isCancelled    -> SAmber.copy(alpha = 0.15f)
        else           -> SError.copy(alpha = 0.12f)
    }
    val icon = when {
        isCancelled            -> "⊘"
        !record.success        -> "✕"
        record.format == "MP3" -> "🎵"
        else                   -> "🎬"
    }
    val statusColor = when {
        record.success -> SSuccess
        isCancelled    -> SAmber
        else           -> SError
    }
    val statusLabel = when {
        record.success -> formatLabel(record.format)
        isCancelled    -> "Cancelled"
        else           -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SCard)
            .clickable {
                if (record.success && record.filePath.isNotBlank()) openFile(context, record.filePath)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 18.sp) }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(record.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Badge(containerColor = if (record.success) SAccentDim else iconBg) {
                    Text(statusLabel, color = statusColor, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                Text(dateStr, fontSize = 11.sp, color = STextSub)
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SError.copy(alpha = 0.1f))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) { Text("✕", fontSize = 12.sp, color = SError) }
    }
}

// ─────────────────────────────────────────────────────────────────
// Reusable composables
// ─────────────────────────────────────────────────────────────────

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SCard)
            .padding(18.dp),
        content = content
    )
}

@Composable
fun FormatChip(icon: String, label: String, selected: Boolean,
               modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) SAccent else SCardAlt, tween(150), label = "chip_bg")
    val tc by animateColorAsState(if (selected) SBg else STextSub, tween(150), label = "chip_text")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = tc)
    }
}

@Composable
fun OpenFolderButton() {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SCard)
            .clickable {
                val opened = tryOpenFolder(context,
                    Intent("com.sec.android.app.myfiles.LAUNCH_MY_FILES").apply {
                        putExtra("start_path", Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            .absolutePath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                            "vnd.android.document/root"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                if (!opened) Toast.makeText(context, "No file manager app found", Toast.LENGTH_SHORT).show()
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(SAccentDim),
            contentAlignment = Alignment.Center
        ) { Text("📁", fontSize = 16.sp) }

        Spacer(Modifier.width(12.dp))

        Column {
            Text("Open Downloads folder", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SText)
            Text("View all saved files", fontSize = 12.sp, color = STextSub)
        }

        Spacer(Modifier.weight(1f))
        Text("›", fontSize = 20.sp, color = STextSub)
    }
}

// ─────────────────────────────────────────────────────────────────
// Utility functions
// ─────────────────────────────────────────────────────────────────

fun openFile(context: Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val mime = when (file.extension.lowercase()) {
        "mp3", "m4a", "opus", "ogg" -> "audio/*"
        "mp4", "mkv", "webm"        -> "video/*"
        else                         -> "*/*"
    }
    val uri = try {
        FileProvider.getUriForFile(context, DownloadService.FILE_PROVIDER_AUTHORITY, file)
    } catch (_: Exception) { Uri.fromFile(file) }

    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {}
}

fun tryOpenFolder(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try { context.startActivity(intent); return true } catch (_: Exception) {}
    }
    return false
}