package app.harvester.companion.settings.addons

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.UserscriptManager
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class ScriptEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val scriptName = intent.getStringExtra("name")
        if (scriptName == null) {
            finish()
            return
        }
        val initialScript = UserscriptManager.loadScript(this, scriptName)
        if (initialScript == null) {
            Toast.makeText(this, "Script not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            UHTheme {
                val context = LocalContext.current
                var originalBody by remember { mutableStateOf(initialScript.body) }
                var body by remember { mutableStateOf(initialScript.body) }
                var showDiscardDialog by remember { mutableStateOf(false) }

                fun confirmExit() {
                    if (body != originalBody) {
                        showDiscardDialog = true
                    } else {
                        finish()
                    }
                }

                BackHandler { confirmExit() }

                if (showDiscardDialog) {
                    AlertDialog(
                        onDismissRequest = { showDiscardDialog = false },
                        title = { Text("Discard changes?", color = MaterialTheme.colorScheme.onSurface) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDiscardDialog = false
                                    finish()
                                }
                            ) {
                                Text("Discard", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiscardDialog = false }) {
                                Text("Keep editing", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = initialScript.displayName.ifBlank { initialScript.name },
                    scrollable = false,
                    onBack = { confirmExit() },
                    actions = {
                        Row(modifier = Modifier.padding(end = 8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val reloaded = UserscriptManager.loadScript(context, scriptName)
                                    if (reloaded != null) {
                                        originalBody = reloaded.body
                                        body = reloaded.body
                                        Toast.makeText(context, "Reloaded from disk", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Reload", fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (body.isBlank()) {
                                        Toast.makeText(context, "Empty script", Toast.LENGTH_SHORT).show()
                                    } else if (UserscriptManager.saveEdited(context, scriptName, body)) {
                                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                        finish()
                                    } else {
                                        Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .imePadding()
                    ) {
                        BasicTextField(
                            value = body,
                            onValueChange = { body = it },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}
