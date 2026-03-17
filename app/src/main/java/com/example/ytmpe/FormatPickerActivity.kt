package com.example.ytmpe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colours matching the main app design tokens
private val FpCard     = Color(0xFF181818)
private val FpCardAlt  = Color(0xFF222222)
private val FpAccent   = Color(0xFF3B82F6)
private val FpText     = Color(0xFFEEEEEE)
private val FpTextSub  = Color(0xFF787878)
private val FpDivider  = Color(0xFF272727)

class FormatPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedUrl.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            FormatPickerSheet(
                url = sharedUrl,
                onFormatSelected = { format ->
                    val serviceIntent = Intent(this, DownloadService::class.java).apply {
                        putExtra(DownloadService.EXTRA_URL, sharedUrl)
                        putExtra(DownloadService.EXTRA_FORMAT, format)
                    }
                    // On Android 14+ (API 34+) release builds, startForegroundService() can throw
                    // ForegroundServiceStartNotAllowedException if the OS considers the app to be
                    // in the background at the moment the call is made (e.g. transparent-theme
                    // activities on Samsung One UI 8 / Android 16). We catch any exception and
                    // fall back to startService() — the service itself calls startForeground()
                    // unconditionally in onStartCommand(), so it promotes itself regardless.
                    try {
                        startForegroundService(serviceIntent)
                    } catch (_: Exception) {
                        try { startService(serviceIntent) } catch (_: Exception) {}
                    }
                    finish()
                },
                onDismiss = { finish() }
            )
        }
    }
}

data class FormatOption(
    val code:     String,
    val glyph:    String,
    val title:    String,
    val subtitle: String
)

private val formatOptions = listOf(
    FormatOption("MP3",      "♪",  "MP3 Audio",       "Audio only · Best quality"),
    FormatOption("MP4_360",  "▶",  "MP4 360p",        "Video · Smaller file size"),
    FormatOption("MP4_1080", "HD", "MP4 1080p",        "Video · Full HD quality"),
)

@Composable
fun FormatPickerSheet(
    url: String,
    onFormatSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume */ }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(FpCard)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(FpTextSub.copy(alpha = 0.35f))
                )
                Spacer(Modifier.height(22.dp))

                // VidTown badge
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(FpAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = "V",
                        color      = Color.White,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text       = "Save as...",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = FpText
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text     = url,
                    fontSize = 12.sp,
                    color    = FpTextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )

                Spacer(Modifier.height(22.dp))

                HorizontalDivider(
                    color     = FpDivider,
                    thickness = 1.dp,
                    modifier  = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(Modifier.height(12.dp))

                formatOptions.forEach { option ->
                    FpFormatCard(option = option) { onFormatSelected(option.code) }
                    Spacer(Modifier.height(10.dp))
                }

                HorizontalDivider(
                    color     = FpDivider,
                    thickness = 1.dp,
                    modifier  = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(FpCardAlt)
                        .border(1.dp, FpDivider, RoundedCornerShape(16.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = "Cancel",
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color      = FpTextSub
                    )
                }
            }
        }
    }
}

@Composable
fun FpFormatCard(option: FormatOption, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isPressed) FpAccent.copy(alpha = 0.1f) else FpCardAlt)
            .border(
                width = 1.dp,
                color = if (isPressed) FpAccent.copy(alpha = 0.45f) else FpDivider,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication        = null
            ) { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FpAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = option.glyph,
                fontSize   = 20.sp,
                color      = FpAccent,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = option.title,
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color      = FpText
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text     = option.subtitle,
                fontSize = 12.sp,
                color    = FpTextSub
            )
        }

        Text(
            text       = "›",
            fontSize   = 24.sp,
            color      = FpTextSub,
            fontWeight = FontWeight.Light
        )
    }
}
