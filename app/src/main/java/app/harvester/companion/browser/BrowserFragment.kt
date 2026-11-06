package app.harvester.companion.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment

class BrowserFragment : Fragment() {
    fun loadUrl(url: String) {
        // Stub for Batch 0
    }

    fun onBackPressed(): Boolean {
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                Text("Browser Fragment Stub - Building Batch 1")
            }
        }
    }

    companion object {
        fun newInstance(url: String) = BrowserFragment().apply {
            arguments = Bundle().apply { putString("url", url) }
        }
    }
}
