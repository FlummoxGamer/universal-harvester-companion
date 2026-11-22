package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.privacy.DohProvider
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.WebRtcPolicy
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current

                var dohCode by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_ADV_DOH_PROVIDER, DohProvider.OFF.code))
                }
                var customDohUrl by remember {
                    mutableStateOf(SettingsPrefs.getString(context, SettingsPrefs.K_ADV_DOH_CUSTOM_URL, ""))
                }
                var webrtcCode by remember {
                    mutableIntStateOf(
                        SettingsPrefs.getInt(
                            context,
                            SettingsPrefs.K_ADV_WEBRTC_POLICY,
                            WebRtcPolicy.DISABLE_NON_PROXIED_UDP.code,
                        ),
                    )
                }

                var showDohDialog by remember { mutableStateOf(false) }
                var showWebRtcDialog by remember { mutableStateOf(false) }

                val dohProviders = DohProvider.entries
                val webrtcPolicies = WebRtcPolicy.entries

                if (showDohDialog) {
                    var draftCustomUrl by remember(customDohUrl) { mutableStateOf(customDohUrl) }
                    var selectedCode by remember(dohCode) { mutableIntStateOf(dohCode) }

                    AlertDialog(
                        onDismissRequest = { showDohDialog = false },
                        title = { Text("Secure DNS (DoH)", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                dohProviders.forEach { provider ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedCode = provider.code }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selectedCode == provider.code,
                                            onClick = { selectedCode = provider.code },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = provider.label,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            if (provider.url.isNotBlank()) {
                                                Text(
                                                    text = provider.url,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                }

                                if (selectedCode == DohProvider.CUSTOM.code) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draftCustomUrl,
                                        onValueChange = { draftCustomUrl = it },
                                        label = { Text("Custom DoH Endpoint") },
                                        placeholder = { Text("https://dns.example.com/dns-query") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (selectedCode == DohProvider.CUSTOM.code && !draftCustomUrl.trim().startsWith("https://")) {
                                        Toast.makeText(context, "Custom DoH URL must start with https://", Toast.LENGTH_SHORT).show()
                                        return@TextButton
                                    }
                                    dohCode = selectedCode
                                    customDohUrl = draftCustomUrl.trim()
                                    SettingsPrefs.setInt(context, SettingsPrefs.K_ADV_DOH_PROVIDER, dohCode)
                                    SettingsPrefs.setString(context, SettingsPrefs.K_ADV_DOH_CUSTOM_URL, customDohUrl)
                                    SettingsPrefs.setBool(context, SettingsPrefs.K_ADV_SECURE_DNS, dohCode != DohProvider.OFF.code)
                                    UhPrivacyController.applyAllToEngine(context)
                                    showDohDialog = false
                                },
                            ) {
                                Text("Save", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDohDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                if (showWebRtcDialog) {
                    ChoiceDialogWithSummary(
                        title = "WebRTC IP Protection",
                        options = webrtcPolicies.map { it.label to it.summary },
                        selectedIndex = webrtcPolicies.indexOfFirst { it.code == webrtcCode }.coerceAtLeast(0),
                        onSelect = { idx ->
                            val chosen = webrtcPolicies[idx]
                            webrtcCode = chosen.code
                            SettingsPrefs.setInt(context, SettingsPrefs.K_ADV_WEBRTC_POLICY, chosen.code)
                            UhPrivacyController.applyAllToEngine(context)
                            showWebRtcDialog = false
                        },
                        onDismiss = { showWebRtcDialog = false },
                    )
                }

                val currentDoh = DohProvider.fromCode(dohCode)
                val dohSubtitle = if (currentDoh == DohProvider.CUSTOM && customDohUrl.isNotBlank()) {
                    "Custom: $customDohUrl"
                } else {
                    currentDoh.label
                }
                val currentWebRtc = WebRtcPolicy.fromCode(webrtcCode)

                UhSettingsScaffold(
                    title = "Advanced Protections",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Browsing & Privacy Signals")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Safe Browsing",
                            subtitle = "Block phishing, malware, and deceptive downloads",
                            key = SettingsPrefs.K_ADV_SAFE_BROWSING,
                            default = true,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto De-AMP",
                            subtitle = "Bypass Google AMP wrappers and load canonical publisher pages",
                            key = SettingsPrefs.K_ADV_DEAMP,
                            default = true,
                            iconRes = R.drawable.fx_ic_link_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Global Privacy Control (GPC)",
                            subtitle = "Send Sec-GPC: 1 header telling sites not to sell your data",
                            key = SettingsPrefs.K_ADV_GPC,
                            default = true,
                            iconRes = R.drawable.fx_ic_lock_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Do Not Track (DNT)",
                            subtitle = "Send legacy DNT: 1 request header to websites",
                            key = SettingsPrefs.K_ADV_DNT,
                            default = false,
                            iconRes = R.drawable.fx_ic_info_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                    }

                    SettingsCategory("Network & IP Hardening")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Secure DNS (DNS-over-HTTPS)",
                            subtitle = dohSubtitle,
                            iconRes = R.drawable.ic_globe,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showDohDialog = true },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "WebRTC IP Protection",
                            subtitle = "${currentWebRtc.label} — ${currentWebRtc.summary}",
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showWebRtcDialog = true },
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
