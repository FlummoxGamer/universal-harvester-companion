package app.harvester.companion.browser.menu

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.harvester.companion.DownloadsActivity
import app.harvester.companion.HistoryActivity
import app.harvester.companion.R
import app.harvester.companion.SettingsActivity
import app.harvester.companion.theme.UHTheme
import com.google.android.material.R as materialR

class MainMenuDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let {
                    ViewCompat.setOnApplyWindowInsetsListener(it) { view, insets ->
                        val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.setPadding(0, systemBarInsets.top, 0, systemBarInsets.bottom)
                        insets
                    }
                }
                bottomSheetBehavior = bottomSheet?.let {
                    BottomSheetBehavior.from(it).apply {
                        maxWidth = calculateMenuSheetWidth()
                        peekHeight = resources.displayMetrics.heightPixels
                        maxHeight = calculateMenuSheetHeight()
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bottomSheetBehavior?.apply {
            maxWidth = calculateMenuSheetWidth()
            maxHeight = calculateMenuSheetHeight()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(36.dp, 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(colorResource(id = R.color.subtext))
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorResource(id = R.color.surface)),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                MenuIconItem("History", R.drawable.ic_history, Modifier.weight(1f)) {
                                    dismiss()
                                    startActivity(Intent(requireContext(), HistoryActivity::class.java))
                                }
                                MenuIconItem("Downloads", R.drawable.ic_downloads, Modifier.weight(1f)) {
                                    dismiss()
                                    startActivity(Intent(requireContext(), DownloadsActivity::class.java))
                                }
                                MenuIconItem("Settings", R.drawable.ic_settings, Modifier.weight(1f)) {
                                    dismiss()
                                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuIconItem(label: String, iconRes: Int, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(80.dp)
                .clickable { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = colorResource(id = R.color.text),
                modifier = Modifier.padding(top = 16.dp).size(24.dp)
            )
            Text(
                text = label,
                color = colorResource(id = R.color.text),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    private fun calculateMenuSheetWidth(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val screenWidth = resources.displayMetrics.widthPixels
        val padding = (32 * resources.displayMetrics.density).toInt()
        return if (isLandscape) screenWidth - padding else screenWidth
    }

    private fun calculateMenuSheetHeight(): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.85f).toInt()
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) screenHeight else maxHeight
    }
}
