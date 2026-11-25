package app.harvester.companion.browser.tabs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import mozilla.components.browser.state.state.TabSessionState

internal enum class TraySection { NORMAL, PRIVATE, GROUPS }

internal sealed interface TrayItem {
    val key: String

    data class GroupCardItem(
        val group: TabGroup,
        val members: List<TabSessionState>
    ) : TrayItem {
        override val key: String get() = "grp_${group.id}"
    }

    data class SingleTabItem(
        val tab: TabSessionState
    ) : TrayItem {
        override val key: String get() = "tab_${tab.id}"
    }
}

internal data class CreateGroupDraft(
    val tabIds: List<String>,
    val isPrivate: Boolean,
    val editingGroup: TabGroup? = null
)

@Immutable
internal data class TabsTrayPalette(
    val isDark: Boolean,
    val isPrivateTheme: Boolean,
    val pageBg: Color,
    val chromeBg: Color,
    val cardBg: Color,
    val previewBg: Color,
    val accent: Color,
    val onAccent: Color,
    val subtleBorder: Color
)

internal val PrivateAccentColor = Color(0xFF9F7AEA)

@Composable
internal fun rememberTabsTrayPalette(
    isDark: Boolean,
    isPrivateSection: Boolean
): TabsTrayPalette {
    val scheme = MaterialTheme.colorScheme
    return if (isPrivateSection) {
        TabsTrayPalette(
            isDark = isDark,
            isPrivateTheme = true,
            pageBg = if (isDark) Color(0xFF0E0A17) else Color(0xFFF6F2FF),
            chromeBg = if (isDark) Color(0xFF171126) else Color(0xFFEDE6FF),
            cardBg = if (isDark) Color(0xFF1E1730) else Color(0xFFFFFFFF),
            previewBg = if (isDark) Color(0xFF140F22) else Color(0xFFF3EEFF),
            accent = PrivateAccentColor,
            onAccent = Color.White,
            subtleBorder = PrivateAccentColor.copy(alpha = if (isDark) 0.30f else 0.28f)
        )
    } else {
        TabsTrayPalette(
            isDark = isDark,
            isPrivateTheme = false,
            pageBg = scheme.background,
            chromeBg = scheme.surfaceContainerHigh,
            cardBg = scheme.surfaceContainerLow,
            previewBg = scheme.surfaceVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            subtleBorder = scheme.outline.copy(alpha = 0.20f)
        )
    }
}

@Immutable
data class TabsTrayActions(
    val onSelectTab: (String) -> Unit,
    val onCloseTab: (String) -> Unit,
    val onCloseTabsBatch: (tabIds: List<String>, label: String, isPrivate: Boolean) -> Unit,
    val onCloseAllTabs: (isPrivate: Boolean) -> Unit,
    val onNewTab: (isPrivate: Boolean) -> Unit,
    val onNewTabInGroup: (groupId: String, isPrivate: Boolean) -> Unit,
    val onDuplicateTab: (TabSessionState) -> Unit,
    val onTogglePinTab: (TabSessionState) -> Unit,
    val onToggleMuteTab: (TabSessionState) -> Unit,
    val onShareTabs: (List<String>) -> Unit,
    val onBookmarkTabs: (List<String>) -> Unit,
    val onCreateGroup: (name: String, colorArgb: Long, tabIds: List<String>, isPrivate: Boolean) -> Unit,
    val onAddTabsToGroup: (groupId: String, tabIds: List<String>) -> Unit,
    val onRemoveTabFromGroup: (String) -> Unit,
    val onRenameGroup: (groupId: String, name: String, colorArgb: Long) -> Unit,
    val onUngroup: (groupId: String) -> Unit,
    val onCloseGroupTabs: (group: TabGroup) -> Unit,
    val onUndoClose: () -> Unit,
    val onRestoreRecentlyClosed: (ClosedTabRecord) -> Unit,
    val onRestoreAllRecentlyClosed: () -> Unit,
    val onClearRecentlyClosed: () -> Unit,
    val onUnlockPrivate: () -> Unit,
    val onPrivateSectionVisible: (Boolean) -> Unit,
    val onPrivateSecurePrefChanged: (Boolean) -> Unit,
    val onDismiss: () -> Unit
)
