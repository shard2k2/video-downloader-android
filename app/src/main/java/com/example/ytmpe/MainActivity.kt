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
import android.os.PowerManager
import android.provider.Settings
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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────
// Design tokens — clean dark + blue, LocalSend-style
// ─────────────────────────────────────────────────────────────────
val Bg       = Color(0xFF0D0D0D)
val Surf     = Color(0xFF181818)
val SurfAlt  = Color(0xFF222222)
val Accent   = Color(0xFF3B82F6)   // clean blue
val OnAccent = Color.White
val Txt      = Color(0xFFEEEEEE)
val TxtSub   = Color(0xFF787878)
val Err      = Color(0xFFEF4444)
val Ok       = Color(0xFF4ADE80)
val Warn     = Color(0xFFF59E0B)
val Div      = Color(0xFF272727)

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

    private val activeDownloads = mutableStateMapOf<String, ActiveDownload>()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadService.ACTION_PROGRESS) return
            val processId  = intent.getStringExtra(DownloadService.EXTRA_PROCESS_ID)  ?: return
            val title      = intent.getStringExtra(DownloadService.EXTRA_TITLE)       ?: ""
            val percent    = intent.getFloatExtra(DownloadService.EXTRA_PERCENT, 0f)
            val statusText = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
            val status     = intent.getStringExtra(DownloadService.EXTRA_STATUS)      ?: ""
            val filePath   = intent.getStringExtra(DownloadService.EXTRA_FILE_PATH)   ?: ""

            if (status == DownloadService.STATUS_REMOVE) {
                activeDownloads.remove(processId)
            } else {
                activeDownloads[processId] = ActiveDownload(
                    processId, title, percent, statusText, status, filePath
                )
            }
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

        // On Android 15+ (and Samsung One UI 7/8), the OS can kill long-running downloads
        // even with a foreground service + WakeLock unless the app is explicitly exempt from
        // battery optimization. We request this once — the system shows a one-tap dialog.
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                // Device doesn't support the dialog — user must do it manually in Settings
            }
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
            background   = Bg,
            surface      = Surf,
            primary      = Accent,
            onBackground = Txt,
            onSurface    = Txt,
        ),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────
// MainScreen
// ─────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(activeDownloads: Map<String, ActiveDownload>) {
    val context    = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope      = rememberCoroutineScope()

    var historyItems by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }

    val finishedStatuses = setOf(
        DownloadService.STATUS_DONE,
        DownloadService.STATUS_FAILED,
        DownloadService.STATUS_CANCELLED
    )

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            historyItems = withContext(Dispatchers.IO) { DownloadHistory.loadAll(context) }
        }
    }

    LaunchedEffect(activeDownloads.values.map { it.status }) {
        if (activeDownloads.values.any { it.status in finishedStatuses }) {
            historyItems = withContext(Dispatchers.IO) { DownloadHistory.loadAll(context) }
        }
    }

    LaunchedEffect(Unit) {
        historyItems = withContext(Dispatchers.IO) { DownloadHistory.loadAll(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        AppHeader()

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor   = Bg,
            contentColor     = Accent,
            indicator = { tabPositions ->
                val cur = tabPositions[pagerState.currentPage]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = cur.left + 24.dp)
                        .width(cur.width - 48.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                )
            },
            divider = {
                HorizontalDivider(color = Div, thickness = 1.dp)
            }
        ) {
            AppTab("Download", pagerState.currentPage == 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            AppTab("My Downloads", pagerState.currentPage == 1) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        }

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
                        historyItems = historyItems.filter { it.id != record.id }
                        scope.launch(Dispatchers.IO) { DownloadHistory.delete(context, record.id) }
                    },
                    onClearAll = {
                        historyItems = emptyList()
                        scope.launch(Dispatchers.IO) { DownloadHistory.clearAll(context) }
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// App header
// ─────────────────────────────────────────────────────────────────
@Composable
fun AppHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Text("V", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text       = "VidTown",
            fontSize   = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Txt
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Tab button
// ─────────────────────────────────────────────────────────────────
@Composable
fun AppTab(title: String, selected: Boolean, onClick: () -> Unit) {
    // animateColorAsState is fine here — tabs are never inside a LazyColumn
    val textColor by animateColorAsState(
        if (selected) Accent else TxtSub, tween(180), label = "tab"
    )
    Tab(
        selected               = selected,
        onClick                = onClick,
        selectedContentColor   = Accent,
        unselectedContentColor = TxtSub
    ) {
        Text(
            text       = title,
            color      = textColor,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize   = 14.sp,
            modifier   = Modifier.padding(vertical = 12.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Download tab
// ─────────────────────────────────────────────────────────────────
@Composable
fun DownloadTab(onDownloadQueued: () -> Unit) {
    val context = LocalContext.current

    var url            by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("MP4_1080") }

    val trimmedUrl  = url.trim()
    val canDownload = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── URL input ─────────────────────────────────────────────
        item {
            SectionCard {
                Text(
                    text       = "URL",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color      = TxtSub,
                    modifier   = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    placeholder   = {
                        Text(
                            "Paste a link...",
                            color    = TxtSub,
                            fontSize = 14.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor        = Txt,
                        unfocusedTextColor      = Txt,
                        focusedBorderColor      = Accent,
                        unfocusedBorderColor    = Div,
                        cursorColor             = Accent,
                        focusedContainerColor   = SurfAlt,
                        unfocusedContainerColor = SurfAlt
                    ),
                    shape      = RoundedCornerShape(10.dp),
                    modifier   = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (url.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text     = "Tip: share from YouTube, Instagram or TikTok directly",
                        fontSize = 12.sp,
                        color    = TxtSub
                    )
                } else if (!canDownload) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text     = "Must be an http/https link",
                        fontSize = 12.sp,
                        color    = Err
                    )
                }
            }
        }

        // ── Format selector ───────────────────────────────────────
        item {
            SectionCard {
                Text(
                    text       = "Format",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color      = TxtSub,
                    modifier   = Modifier.padding(bottom = 10.dp)
                )
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple("MP3",      "MP3",   "Audio"),
                        Triple("MP4_360",  "360p",  "Low"),
                        Triple("MP4_1080", "1080p", "HD"),
                    ).forEach { (code, tag, label) ->
                        // No animateColorAsState here — direct color avoids per-item animation overhead
                        val isSelected = selectedFormat == code
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Accent else SurfAlt)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Accent else Div,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication        = null
                                ) { selectedFormat = code }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text       = tag,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color      = if (isSelected) Color.White else Txt
                            )
                            Text(
                                text     = label,
                                fontSize = 11.sp,
                                color    = if (isSelected) Color.White.copy(alpha = 0.75f) else TxtSub
                            )
                        }
                    }
                }
            }
        }

        // ── Download button ───────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canDownload) Accent else Surf)
                    .border(1.dp, if (canDownload) Accent else Div, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        enabled           = canDownload
                    ) {
                        val serviceIntent = Intent(context, DownloadService::class.java).apply {
                            putExtra(DownloadService.EXTRA_URL, url)
                            putExtra(DownloadService.EXTRA_FORMAT, selectedFormat)
                        }
                        try {
                            context.startForegroundService(serviceIntent)
                        } catch (_: Exception) {
                            try { context.startService(serviceIntent) } catch (_: Exception) {}
                        }
                        url = ""
                        onDownloadQueued()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = "Download",
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (canDownload) Color.White else TxtSub
                )
            }
        }

        item { OpenFolderButton() }
    }
}

// ─────────────────────────────────────────────────────────────────
// Downloads tab
// ─────────────────────────────────────────────────────────────────
@Composable
fun DownloadsTab(
    activeDownloads: Map<String, ActiveDownload>,
    historyItems:    List<DownloadRecord>,
    onDelete:        (DownloadRecord) -> Unit,
    onClearAll:      () -> Unit
) {
    val context = LocalContext.current

    val running = activeDownloads.values
        .filter { it.status == DownloadService.STATUS_ACTIVE || it.status == DownloadService.STATUS_QUEUED }
        .sortedBy { it.processId }
    val finished = activeDownloads.values
        .filter { it.status in setOf(DownloadService.STATUS_DONE, DownloadService.STATUS_FAILED, DownloadService.STATUS_CANCELLED) }

    val activeIds     = activeDownloads.keys
    val historyToShow = historyItems.filter { record ->
        activeIds.none { id -> id.removePrefix("dl_").toLongOrNull() == record.id }
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (running.isNotEmpty()) {
            item { ListLabel("Active") }
            items(running, key = { it.processId }) { dl ->
                ActiveDownloadCard(dl)
            }
        }

        if (finished.isNotEmpty()) {
            item { ListLabel("Just Finished") }
            items(finished, key = { it.processId }) { dl ->
                FinishedDownloadCard(dl, context)
            }
        }

        if (historyToShow.isNotEmpty()) {
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ListLabel("History")
                    Text(
                        text     = "Clear all",
                        fontSize = 12.sp,
                        color    = Err.copy(alpha = 0.8f),
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
                Column(
                    modifier            = Modifier.fillMaxWidth().padding(top = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier         = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Surf),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↓", fontSize = 28.sp, color = TxtSub)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No downloads yet", fontSize = 16.sp, color = Txt, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Paste a link in the Download tab", fontSize = 13.sp, color = TxtSub)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Active download card
// ─────────────────────────────────────────────────────────────────
@Composable
fun ActiveDownloadCard(dl: ActiveDownload) {
    val context  = LocalContext.current
    val isQueued = dl.status == DownloadService.STATUS_QUEUED

    // animateFloatAsState is fine here — active downloads are never more than a handful
    val animatedPercent by animateFloatAsState(
        targetValue   = dl.percent / 100f,
        animationSpec = tween(300),
        label         = "p_${dl.processId}"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfAlt),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text     = if (isQueued) "…" else "↓",
                    fontSize = 16.sp,
                    color    = if (isQueued) TxtSub else Accent
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = dl.title,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color      = Txt,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = dl.statusText,
                    fontSize = 12.sp,
                    color    = TxtSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isQueued) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SurfAlt)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) {
                            context.sendBroadcast(
                                Intent(DownloadService.ACTION_CANCEL).apply {
                                    setPackage(context.packageName)
                                    putExtra(DownloadService.EXTRA_CANCEL_PROCESS_ID, dl.processId)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 11.sp, color = TxtSub)
                }
            }
        }

        if (!isQueued) {
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfAlt)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedPercent.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                )
            }

            if (dl.percent > 0f) {
                Spacer(Modifier.height(4.dp))
                Text("${dl.percent.toInt()}%", fontSize = 11.sp, color = TxtSub)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Just-finished card
// ─────────────────────────────────────────────────────────────────
@Composable
fun FinishedDownloadCard(dl: ActiveDownload, context: Context) {
    val isDone      = dl.status == DownloadService.STATUS_DONE
    val isCancelled = dl.status == DownloadService.STATUS_CANCELLED

    // Direct color — no animation needed here, these appear once
    val statusColor = when {
        isDone      -> Ok
        isCancelled -> Warn
        else        -> Err
    }
    val label = when {
        isDone      -> "Done"
        isCancelled -> "Cancelled"
        else        -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .clickable(enabled = isDone && dl.filePath.isNotBlank()) {
                openFile(context, dl.filePath)
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfAlt),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text     = if (isDone) "✓" else if (isCancelled) "–" else "✕",
                fontSize = 15.sp,
                color    = statusColor
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = dl.title,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                color      = Txt,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(text = label, fontSize = 12.sp, color = statusColor)
        }

        if (isDone) {
            Text("›", fontSize = 20.sp, color = TxtSub)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// History card
// ─────────────────────────────────────────────────────────────────
@Composable
fun HistoryCard(record: DownloadRecord, onDelete: () -> Unit) {
    val context     = LocalContext.current
    val isCancelled = record.status == "cancelled"
    val dateStr     = remember(record.timestamp) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    // Direct color — no animation inside scroll list
    val statusColor = when {
        record.success -> Ok
        isCancelled    -> Warn
        else           -> Err
    }
    val statusLabel = when {
        record.success -> formatLabel(record.format)
        isCancelled    -> "Cancelled"
        else           -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .clickable {
                if (record.success && record.filePath.isNotBlank()) openFile(context, record.filePath)
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfAlt),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text     = when {
                    isCancelled            -> "–"
                    !record.success        -> "✕"
                    record.format == "MP3" -> "♪"
                    else                   -> "▶"
                },
                fontSize = 16.sp,
                color    = statusColor
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = record.title,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                color      = Txt,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(text = statusLabel, fontSize = 11.sp, color = statusColor)
                Text("·", fontSize = 11.sp, color = Div)
                Text(text = dateStr, fontSize = 11.sp, color = TxtSub)
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(SurfAlt)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null
                ) { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 11.sp, color = TxtSub)
        }
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
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun ListLabel(text: String) {
    Text(
        text          = text,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.Medium,
        color         = TxtSub,
        letterSpacing = 0.5.sp,
        modifier      = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

@Composable
fun OpenFolderButton() {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .clickable {
                val opened = tryOpenFolder(
                    context,
                    Intent("com.sec.android.app.myfiles.LAUNCH_MY_FILES").apply {
                        putExtra(
                            "start_path",
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                        )
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
                if (!opened) Toast.makeText(context, "No file manager found", Toast.LENGTH_SHORT).show()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfAlt),
            contentAlignment = Alignment.Center
        ) {
            Text("📁", fontSize = 16.sp)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text("Open Downloads folder", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Txt)
            Text("View all saved files", fontSize = 12.sp, color = TxtSub)
        }

        Text("›", fontSize = 20.sp, color = TxtSub)
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
