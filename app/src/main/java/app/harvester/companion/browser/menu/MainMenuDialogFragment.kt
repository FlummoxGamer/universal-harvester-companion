package app.harvester.companion.browser.menu

import android.app.Dialog
import android.content.Intent
import androidx.navigation.fragment.findNavController
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.harvester.companion.HistoryActivity
import app.harvester.companion.R
import app.harvester.companion.ScriptsActivity
import app.harvester.companion.SettingsActivity
import app.harvester.companion.UHApp
import app.harvester.companion.theme.UHTheme
import mozilla.components.browser.state.selector.selectedTab
import com.google.android.material.R as materialR

class MainMenuDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    companion object {
        private const val ARG_IS_HOME = "is_home"
        fun newInstance(isHome: Boolean): MainMenuDialogFragment {
            val args = Bundle().apply { putBoolean(ARG_IS_HOME, isHome) }
            return MainMenuDialogFragment().apply { arguments = args }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let {
                    bottomSheetBehavior = BottomSheetBehavior.from(it).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
                bottomSheet?.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isHome = arguments?.getBoolean(ARG_IS_HOME) ?: true
        val components = (requireActivity().application as UHApp).components
        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val browserState by components.store.stateFlow.collectAsState()
                    val selectedTab = browserState.selectedTab
                    val isDesktopMode = selectedTab?.content?.desktopMode == true

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.surface_3), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp, 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colorResource(R.color.subtext))
                                .align(Alignment.CenterHorizontally)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TopRowItem("Favorites", R.drawable.ic_favorites, Modifier.weight(1f)) {
                                dismiss()
                            }
                            TopRowItem("History", R.drawable.ic_history, Modifier.weight(1f)) {
                                dismiss()
                                val navHostFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment
                                navHostFragment?.navController?.navigate(R.id.historyFragment)
                            }
                            TopRowItem("Downloads", R.drawable.ic_downloads, Modifier.weight(1f)) {
                                dismiss()
                                val navHostFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment
                                navHostFragment?.navController?.navigate(R.id.action_global_downloadsFragment)
                            }
                            TopRowItem("Playlist", R.drawable.ic_playlist, Modifier.weight(1f)) {
                                dismiss()
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(colorResource(R.color.surface))
                                .padding(vertical = 8.dp)
                        ) {
                            if (!isHome) {
                                MenuRowItem(R.drawable.ic_menu_translate, "Translate") { dismiss() }
                                MenuRowItem(R.drawable.ic_menu_find, "Find in Page") { dismiss() }
                                MenuRowItem(
                                    iconRes = if (isDesktopMode) R.drawable.ic_menu_mobile else R.drawable.ic_menu_desktop,
                                    label = if (isDesktopMode) "Request Mobile Site" else "Request Desktop Site"
                                ) {
                                    dismiss()
                                    selectedTab?.let { tab ->
                                        components.useCases.sessionUseCases.requestDesktopSite(!isDesktopMode, tab.id)
                                    }
                                }
                                MenuRowItem(R.drawable.ic_menu_add_home, "Add to Home Screen") { dismiss() }
                                MenuRowItem(R.drawable.ic_menu_dev, "Developer Tools") { dismiss() }
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .height(1.dp)
                                        .background(colorResource(R.color.border))
                                )
                            }

                            MenuRowItem(R.drawable.ic_settings, "Settings") {
                                dismiss()
                                startActivity(Intent(requireContext(), SettingsActivity::class.java))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(32.dp))
                                .background(colorResource(R.color.surface))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarItem("Home", R.drawable.ic_home) { dismiss() }
                            BottomBarItem("Add", R.drawable.ic_heart_plus) { dismiss() }
                            BottomBarItem("Quit", R.drawable.ic_power) { dismiss(); requireActivity().finish() }
                            BottomBarItem("Scripts", R.drawable.ic_code) {
                                dismiss()
                                startActivity(Intent(requireContext(), ScriptsActivity::class.java))
                            }
                            BottomBarItem("Reload", R.drawable.ic_reload) { dismiss() }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun TopRowItem(label: String, iconRes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colorResource(R.color.surface_2))
                .clickable { onClick() }
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = colorResource(R.color.accent),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = colorResource(R.color.text),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun MenuRowItem(iconRes: Int, label: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = colorResource(R.color.subtext),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                color = colorResource(R.color.text),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun BottomBarItem(label: String, iconRes: Int, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = colorResource(R.color.text),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
