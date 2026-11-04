package app.harvester.companion

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import app.harvester.companion.utils.phoenix.ProcessPhoenix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SafeModeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val crashDir = File(filesDir, "crashes")
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() }
        val latestCrash = crashFiles?.firstOrNull()
        val logText = if (latestCrash != null && latestCrash.exists()) latestCrash.readText() else "No crash log found."

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorResource(id = R.color.bg)
                ) {
                    SafeModeScreen(
                        logText = logText,
                        onRestart = {
                            stopAllProcesses()
                            clearCrashState()
                            ProcessPhoenix.triggerRebirth(this)
                        },
                        onClearCache = {
                            stopAllProcesses()
                            try { cacheDir.deleteRecursively() } catch (_: Exception) {}
                            clearCrashState()
                            ProcessPhoenix.triggerRebirth(this)
                        },
                        onClearData = {
                            stopAllProcesses()
                            try {
                                cacheDir.deleteRecursively()
                                filesDir.deleteRecursively()
                                getExternalFilesDir(null)?.deleteRecursively()
                            } catch (_: Exception) {}
                            clearCrashState()
                            ProcessPhoenix.triggerRebirth(this)
                        },
                        onSaveLog = {
                            try {
                                if (latestCrash != null && latestCrash.exists()) {
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val fname = "uh_crash_$ts.txt"
                                    val ok = DownloadHelper.saveTextToDownloads(this, fname, logText)
                                    Toast.makeText(this, if (ok) "Saved to Downloads" else "Save failed", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "No crash log found", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }

    private fun clearCrashState() {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) crashFile.delete()
            val crashDir = File(filesDir, "crashes")
            crashDir.deleteRecursively()
            val geckoDir = File(filesDir, "mozilla")
            if (geckoDir.exists()) geckoDir.deleteRecursively()
        } catch (_: Exception) {}
        getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().putBoolean("force_safe_mode", false).apply()
    }

    private fun stopAllProcesses() {
        try {
            DownloadStore.activeDownloads.value.forEach { DownloadEngine.cancelDownload(this, it.id) }
            stopService(android.content.Intent(this, DownloadService::class.java))
            stopService(android.content.Intent(this, SessionService::class.java))
            WorkManager.getInstance(this).cancelAllWork()
        } catch (_: Exception) {}
    }
}

@Composable
fun SafeModeScreen(
    logText: String,
    onRestart: () -> Unit,
    onClearCache: () -> Unit,
    onClearData: () -> Unit,
    onSaveLog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Safe Mode",
            color = colorResource(id = R.color.text),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
        )
        
        Text(
            text = "The app crashed on the last launch. Choose a recovery option below.",
            color = colorResource(id = R.color.subtext),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = colorResource(id = R.color.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            androidx.compose.foundation.ScrollState
            val scrollState = rememberScrollState()
            Text(
                text = logText,
                color = colorResource(id = R.color.text),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SafeModeTile(
                    title = "Restart",
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                )
                SafeModeTile(
                    title = "Clear Cache",
                    onClick = onClearCache,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SafeModeTile(
                    title = "Clear Data",
                    onClick = onClearData,
                    modifier = Modifier.weight(1f)
                )
                SafeModeTile(
                    title = "Save Logs",
                    onClick = onSaveLog,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun SafeModeTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(colorResource(id = R.color.surface))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = colorResource(id = R.color.text),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
