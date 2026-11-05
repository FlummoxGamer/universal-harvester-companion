package app.harvester.companion.browser.delegates

import android.app.AlertDialog
import android.content.Context
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class PromptDelegate(private val context: Context) : GeckoSession.PromptDelegate {
    override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val choices = prompt.choices
        if (choices.isEmpty()) {
            result.complete(prompt.dismiss())
            return result
        }
        val labels = choices.map { it.label }.toTypedArray()
        (context as? android.app.Activity)?.runOnUiThread {
            AlertDialog.Builder(context)
                .setTitle(prompt.message ?: "Select an option")
                .setItems(labels) { _, which ->
                    result.complete(prompt.confirm(choices[which]))
                }
                .setOnCancelListener {
                    result.complete(prompt.dismiss())
                }
                .show()
        }
        return result
    }
}
