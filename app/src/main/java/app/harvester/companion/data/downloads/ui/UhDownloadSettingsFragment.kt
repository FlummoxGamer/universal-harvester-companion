package app.harvester.companion.data.downloads.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import app.harvester.companion.theme.UHTheme

class UhDownloadSettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = content {
        UHTheme {
            UhDownloadSettingsScreen(
                onBack = { findNavController().popBackStack() }
            )
        }
    }
}
