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

// Colours reused from MainActivity design tokens
private val SheetBg     = Color(0xFF1C1C26)
private val CardBg      = Color(0xFF252532)
private val CardBorder  = Color(0xFF35354A)
private val AccentColor = Color(0xFF00E5CC)
private val TextMain    = Color(0xFFF0F0F5)
private val TextSub     = Color(0xFF8888AA)
private val DividerCol  = Color(0xFF2E2E3E)

class FormatPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedUrl.isNullOrBlank()) {
            finishAndRemoveTask()
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
                    startForegroundService(serviceIntent)
                    finishAndRemoveTask()
                },
                onDismiss = { finishAndRemoveTask() }
            )
        }
    }
}

// -----------------------------------------------------------------
// Data describing each format option shown in the picker
// -----------------------------------------------------------------
// No modifier = public by default. Must match visibility of FormatCard.
data class FormatOption(
    val code: String,        // internal format code passed to DownloadService
    val icon: String,        // emoji icon
    val title: String,       // bold label
    val subtitle: String     // description line
)

private val formatOptions = listOf(
    FormatOption("MP3",      "🎵", "MP3 Audio",       "Audio only · Best quality"),
    FormatOption("MP4_360",  "📹", "MP4 Low Quality", "Video · Smaller file size"),
    FormatOption("MP4_1080", "🎬", "MP4 1080p",       "Video · Full HD quality"),
)

// -----------------------------------------------------------------
// Root composable — dark dimmed background + sheet on bottom
// -----------------------------------------------------------------
@Composable
fun FormatPickerSheet(
    url: String,
    onFormatSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // tapping the dim area dismisses the sheet
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // The sheet itself — stop tap propagation so clicking inside
        // doesn't dismiss the sheet
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume click so it doesn't reach dim background */ }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(SheetBg)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // ── Drag handle ──────────────────────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(TextSub.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.height(20.dp))

                // ── Header ───────────────────────────────────────────
                Text(
                    text = "Save as",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Show a trimmed version of the URL so mom can see what's queued
                Text(
                    text = url,
                    fontSize = 12.sp,
                    color = TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── Divider ──────────────────────────────────────────
                HorizontalDivider(
                    color = DividerCol,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                formatOptions.forEach { option ->
                    FormatCard(option = option) { onFormatSelected(option.code) }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ── Divider ──────────────────────────────────────────
                HorizontalDivider(
                    color = DividerCol,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Cancel button ────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .clickable { onDismiss() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------
// Single format option card
// -----------------------------------------------------------------
@Composable
fun FormatCard(option: FormatOption, onClick: () -> Unit) {
    // collectIsPressedAsState() reads the press state directly from
    // the interactionSource — no manual boolean needed, no warning
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPressed) AccentColor.copy(alpha = 0.08f) else CardBg)
            .border(
                width = 1.dp,
                color = if (isPressed) AccentColor.copy(alpha = 0.5f) else CardBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AccentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = option.icon, fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = option.subtitle,
                fontSize = 12.sp,
                color = TextSub
            )
        }

        // Chevron arrow
        Text(
            text = "›",
            fontSize = 22.sp,
            color = TextSub,
            fontWeight = FontWeight.Light
        )
    }
}