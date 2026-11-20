package app.harvester.companion.data.downloads.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.theme.UHTheme

class UhDownloadSettingsFragment : Fragment() {
    private var isNavigatingBack = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = content {
        UHTheme {
            UhDownloadSettingsScreen(
                onBack = { navigateBack() }
            )
        }
    }

    private fun navigateBack() {
        if (!isAdded || isNavigatingBack) return
        isNavigatingBack = true
        try {
            if (parentFragment is NavHostFragment) {
                val navController = findNavController()
                if (!navController.popBackStack(R.id.downloadSettingsFragment, true)) {
                    navController.popBackStack()
                }
                return
            }
        } catch (e: Exception) {
            UHLog.w("ui_warn", "NavController popBackStack fallback in Download Settings: ${e.message}")
        }

        try {
            if (!parentFragmentManager.isStateSaved && parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        } catch (e: Exception) {
            UHLog.throwable("ui_error", "Failed navigating back from Download Settings", e)
            isNavigatingBack = false
        }
    }
}
