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
    val headerFrostBg: Color,
    val accent: Color,
    val accentStrong: Color,
    val onAccent: Color,
    val onSurface: Color,
    val onSurfaceSubtle: Color,
    val subtleBorder: Color,
    val glassHighlight: Color,
)

internal val PrivateAccentColor = Color(0xFFA78BFA)
internal val PrivateAccentStrongColor = Color(0xFF7C3AED)
internal val PrivateObsidianBg = Color(0xFF090711)

@Composable
internal fun rememberTabsTrayPalette(
    isDark: Boolean,
    isPrivateSection: Boolean
): TabsTrayPalette {
    val scheme = MaterialTheme.colorScheme
    return if (isPrivateSection) {
        // Always Deep Obsidian + subtle violet stealth glow for Private Browsing
        TabsTrayPalette(
            isDark = true,
            isPrivateTheme = true,
            pageBg = PrivateObsidianBg,
            chromeBg = Color(0xEE140F24),
            cardBg = Color(0xFF140F24),
            previewBg = Color(0xFF0E0A1B),
            headerFrostBg = Color(0xFF18122B),
            accent = PrivateAccentColor,
            accentStrong = PrivateAccentStrongColor,
            onAccent = Color.White,
            onSurface = Color(0xFFF3EEFF),
            onSurfaceSubtle = Color(0xFFA89BC8),
            subtleBorder = PrivateAccentColor.copy(alpha = 0.24f),
            glassHighlight = Color(0xFFC4B5FD).copy(alpha = 0.14f),
        )
    } else {
        TabsTrayPalette(
            isDark = isDark,
            isPrivateTheme = false,
            pageBg = scheme.background,
            chromeBg = scheme.background.copy(alpha = 0.96f),
            cardBg = scheme.surface,
            previewBg = scheme.surfaceVariant,
            headerFrostBg = scheme.background,
            accent = scheme.primary,
            accentStrong = scheme.primary,
            onAccent = scheme.onPrimary,
            onSurface = scheme.onSurface,
            onSurfaceSubtle = scheme.onSurfaceVariant,
            subtleBorder = scheme.outline.copy(alpha = if (isDark) 0.22f else 0.16f),
            glassHighlight = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f),
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
    val onAutoGroupByDomain: (isPrivate: Boolean) -> Unit,
    val onReorderGroupMembers: (groupId: String, orderedTabIds: List<String>) -> Unit,
    val onReorderGroupsDirectory: (orderedGroupIds: List<String>, isPrivate: Boolean) -> Unit,
    val onUndoClose: () -> Unit,
    val onRestoreRecentlyClosed: (ClosedTabRecord) -> Unit,
    val onRestoreAllRecentlyClosed: () -> Unit,
    val onClearRecentlyClosed: () -> Unit,
    val onUnlockPrivate: () -> Unit,
    val onPrivateSectionVisible: (Boolean) -> Unit,
    val onPrivateSecurePrefChanged: (Boolean) -> Unit,
    val onOpenTabSettings: () -> Unit,
    val onDismiss: () -> Unit
)
