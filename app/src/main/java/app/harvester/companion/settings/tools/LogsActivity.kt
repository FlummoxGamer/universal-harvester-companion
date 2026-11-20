package app.harvester.companion.settings.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.BuildConfig
import app.harvester.companion.DebugPrefs
import app.harvester.companion.DownloadHelper
import app.harvester.companion.UHLog
import app.harvester.companion.settings.components.SettingsSwitchItem
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : AppCompatActivity() {

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var logText by remember { mutableStateOf(UHLog.snapshot()) }
                var lineCount by remember { mutableIntStateOf(UHLog.size()) }
                var byteCount by remember { mutableLongStateOf(UHLog.fileSize()) }
                var verbose by remember { mutableStateOf(DebugPrefs.verboseLogging(context)) }
                val logScroll = rememberScrollState()

                fun refresh() {
                    logText = UHLog.snapshot()
                    lineCount = UHLog.size()
                    byteCount = UHLog.fileSize()
                }

                DisposableEffect(Unit) {
                    val listener: (String) -> Unit = { entry ->
                        runOnUiThread {
                            logText = if (logText.isEmpty()) entry else "$logText\n$entry"
                            lineCount = UHLog.size()
                            byteCount = UHLog.fileSize()
                        }
                    }
                    UHLog.addListener(listener)
                    onDispose {
                        UHLog.removeListener(listener)
                    }
                }

                LaunchedEffect(logText.length) {
                    logScroll.animateScrollTo(logScroll.maxValue)
                }

                UhSettingsScaffold(
                    title = "Logs",
                    subtitle = "$lineCount lines · $byteCount bytes · v${BuildConfig.VERSION_NAME}",
                    scrollable = false,
                    onBack = { finish() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        UhSettingsCardGroup {
                            SettingsSwitchItem(
                                title = "Verbose logging",
                                subtitle = "Capture full network and engine trace details",
                                checked = verbose,
                                insideCard = true,
                                onCheckedChange = { checked ->
                                    verbose = checked
                                    DebugPrefs.setVerboseLogging(context, checked)
                                    UHLog.setVerbose(checked)
                                    Toast.makeText(
                                        context,
                                        if (checked) "Verbose on" else "Verbose off",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { refresh() },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Refresh") }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                        val fname = "uh_log_$ts.txt"
                                        val ok = DownloadHelper.saveTextToDownloads(context, fname, UHLog.snapshot())
                                        Toast.makeText(
                                            context,
                                            if (ok) "Saved to Downloads/$fname" else "Save failed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Save") }

                            OutlinedButton(
                                onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("UH Logs", UHLog.snapshot()))
                                    Toast.makeText(context, "Copied ${UHLog.size()} lines", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Copy") }

                            OutlinedButton(
                                onClick = {
                                    UHLog.d("diag-test", "Normal log message")
                                    UHLog.e("diag-test", "Error test message")
                                    UHLog.w("diag-test", "Warning test message")
                                    UHLog.writeLeakLog("Simulated Memory Leak Test")
                                    UHLog.netNav("GET", "https://test.url/diagnostic", null, false, false)
                                    Toast.makeText(context, "Diagnostics logged and mirrored", Toast.LENGTH_SHORT).show()
                                    refresh()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Diagnostics") }

                            OutlinedButton(
                                onClick = {
                                    UHLog.clear()
                                    refresh()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Clear") }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        SelectionContainer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(14.dp)
                                .verticalScroll(logScroll)
                        ) {
                            Text(
                                text = logText.ifBlank { "No logs yet." },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        UHLog.onActivityPause(this)
    }
}
