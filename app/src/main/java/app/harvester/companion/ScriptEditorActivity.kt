package app.harvester.companion

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var titleView: TextView
    private var scriptName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)

        scriptName = intent.getStringExtra("name")
        titleView = findViewById(R.id.editorTitle)
        editor = findViewById(R.id.editorBody)

        val name = scriptName
        if (name == null) { finish(); return }
        val script = UserscriptManager.loadScript(this, name)
        if (script == null) {
            Toast.makeText(this, "Script not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        titleView.text = script.displayName.ifBlank { script.name }
        editor.setText(script.body)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { confirmExit() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { reload() }
    }

    private fun save() {
        val name = scriptName ?: return
        val body = editor.text.toString()
        if (body.isBlank()) {
            Toast.makeText(this, "Empty script", Toast.LENGTH_SHORT).show()
            return
        }
        if (UserscriptManager.saveEdited(this, name, body)) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reload() {
        val name = scriptName ?: return
        val script = UserscriptManager.loadScript(this, name) ?: return
        editor.setText(script.body)
        Toast.makeText(this, "Reloaded from disk", Toast.LENGTH_SHORT).show()
    }

    private fun confirmExit() {
        val original = scriptName?.let { UserscriptManager.loadScript(this, it)?.body } ?: ""
        if (editor.text.toString() != original) {
            AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null)
                .show()
        } else finish()
    }
}
