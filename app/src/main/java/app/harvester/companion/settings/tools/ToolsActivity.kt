package app.harvester.companion.settings.tools

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.SessionRecorder
import app.harvester.companion.UHLog
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchItem
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class ToolsActivity : AppCompatActivity() {

    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        SessionRecorder.init(this)

        setContent {
            UHTheme {
                val context = LocalContext.current
                val tick = refreshTick
                var active by remember(tick) { mutableStateOf(SessionRecorder.isActive()) }
                var rawMode by remember(tick) { mutableStateOf(SessionRecorder.isRawMode()) }
                var showResetConfirm by remember { mutableStateOf(false) }

                val categoryStates = remember(tick) {
                    mutableStateMapOf<String, Boolean>().apply {
                        SessionRecorder.ALL_CATEGORIES.forEach { key ->
                            put(key, SessionRecorder.isEnabled(key))
                        }
                    }
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset session?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Discards all captured events from the current session. Session ID stays the same.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showResetConfirm = false
                                    SessionRecorder.reset()
                                    Toast.makeText(context, "Session reset", Toast.LENGTH_SHORT).show()
                                    refreshTick++
                                }
                            ) {
                                Text("Reset", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Capture Tools",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Session Status")
                    UhSettingsCardGroup {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (active) androidx.compose.ui.graphics.Color(0xFF4ADE80)
                                            else androidx.compose.ui.graphics.Color(0xFF8A96A8)
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (active) "Capturing" else "Idle",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Session ${UHLog.sessionId} · ${SessionRecorder.count()} events · ${SessionRecorder.filteredCount()} filtered · ${SessionRecorder.duplicateCount()} dupes",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        SessionRecorder.start()
                                        active = SessionRecorder.isActive()
                                        refreshTick++
                                    },
                                    enabled = !active,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Start")
                                }
                                OutlinedButton(
                                    onClick = {
                                        SessionRecorder.stop()
                                        active = SessionRecorder.isActive()
                                        refreshTick++
                                    },
                                    enabled = active,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Stop")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val file = SessionRecorder.save(context)
                                        SessionRecorder.stop()
                                        if (file != null) {
                                            Toast.makeText(context, "Saved + session ended: $file", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Nothing to save", Toast.LENGTH_SHORT).show()
                                        }
                                        active = SessionRecorder.isActive()
                                        refreshTick++
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Save")
                                }
                                OutlinedButton(
                                    onClick = { showResetConfirm = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Reset")
                                }
                            }
                        }
                    }

                    SettingsCategory("Recording Mode")
                    UhSettingsCardGroup {
                        SettingsSwitchItem(
                            title = "Raw mode",
                            subtitle = "Capture unfiltered payloads without deduplication",
                            checked = rawMode,
                            insideCard = true,
                            onCheckedChange = { checked ->
                                rawMode = checked
                                SessionRecorder.setRawMode(checked)
                            }
                        )
                    }

                    SettingsCategory("Capture Categories")
                    UhSettingsCardGroup {
                        SessionRecorder.ALL_CATEGORIES.forEachIndexed { index, key ->
                            if (index > 0) UhSettingsDivider()
                            val checked = categoryStates[key] ?: false
                            SettingsSwitchItem(
                                title = prettyLabel(key),
                                checked = checked,
                                insideCard = true,
                                onCheckedChange = { enabled ->
                                    categoryStates[key] = enabled
                                    SessionRecorder.setEnabled(key, enabled)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    private fun prettyLabel(key: String): String = when (key) {
        "header" -> "Request Headers"
        "wasm_blob" -> "WASM Blob"
        "wasm_memory" -> "WASM Memory"
        "crypto" -> "crypto.subtle"
        "atob" -> "atob"
        "textdecoder" -> "TextDecoder"
        "worker_fetch" -> "Worker Fetches"
        "sw_fetch" -> "Service Worker Fetches"
        "wasm_call" -> "WASM Call Trace"
        else -> key
    }
}
