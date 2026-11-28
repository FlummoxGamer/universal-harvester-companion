package app.harvester.companion.browser.toolbar

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UHAddressBar(
    url: String,
    isEditing: Boolean,
    tabCount: Int,
    iconState: Int,
    isWallpaperActive: Boolean,
    editText: String,
    progress: Int = 0,
    isLoading: Boolean = false,
    scriptMatchCount: Int = 0,
    isCaptureActive: Boolean = false,
    isDesktopMode: Boolean = false,
    isPrivate: Boolean = false,
    isCurrentTabPinned: Boolean = false,
    isCurrentTabMuted: Boolean = false,
    zoomPercent: Int = 100,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    isBarBottom: Boolean = true,
    swipeFullscreenEnabled: Boolean = false,
    onEnterImmersiveFullscreen: () -> Unit = {},
    onEditTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditStart: () -> Unit,
    onClear: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onLockClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onModeBadgeClick: () -> Unit = {},
    onReaderClick: () -> Unit = {},
    onReloadStopClick: () -> Unit = {},
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    onQuickNewTab: () -> Unit = {},
    onQuickNewPrivateTab: () -> Unit = {},
    onQuickDuplicateTab: () -> Unit = {},
    onQuickTogglePinTab: () -> Unit = {},
    onQuickToggleMuteTab: () -> Unit = {},
    onQuickCloseCurrentTab: () -> Unit = {},
    onQuickCloseAllTabs: () -> Unit = {},
    searchEngine: SearchEnginePrefs.Entry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val barHeight = if (isLandscape) 50.dp else 58.dp
    val pillHeight = if (isLandscape) 40.dp else 46.dp
    val showFullUrl = SettingsPrefs.getBool(context, SettingsPrefs.K_SHOW_FULL_URL, false)
    val keyboardController = LocalSoftwareKeyboardController.current
    var showTabQuickMenu by remember { mutableStateOf(false) }

    fun formatUrl(rawUrl: String): String {
        var cleanUrl = rawUrl
        if (cleanUrl.startsWith("https://")) cleanUrl = cleanUrl.removePrefix("https://")
        if (cleanUrl.startsWith("http://")) cleanUrl = cleanUrl.removePrefix("http://")
        if (!showFullUrl) {
            if (cleanUrl.startsWith("www.")) cleanUrl = cleanUrl.removePrefix("www.")
            cleanUrl = cleanUrl.substringBefore("/")
        }
        return cleanUrl
    }

    val textState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(url, isEditing, editText) {
        val targetText = if (!isEditing) formatUrl(url) else editText
        if (textState.text.toString() != targetText) {
            textState.edit {
                replace(0, length, targetText)
                placeCursorAtEnd()
            }
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            kotlinx.coroutines.delay(80)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }
            .distinctUntilChanged()
            .collect { typed ->
                if (isEditing && typed != editText) {
                    onEditTextChanged(typed)
                }
            }
    }

    val pillShape = RoundedCornerShape(24.dp)
    val pillBackground = if (isWallpaperActive) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val outerBarColor = if (isWallpaperActive || isEditing) Color.Transparent else MaterialTheme.colorScheme.background
    val hasPageUrl = !isWallpaperActive && url.isNotBlank() && url != "about:blank"

    val pillBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val swipeThresholdPx = with(LocalDensity.current) { 22.dp.toPx() }
    val swipeFullscreenModifier = if (swipeFullscreenEnabled && !isEditing && hasPageUrl) {
        Modifier.pointerInput(isBarBottom, swipeFullscreenEnabled, hasPageUrl) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startPos = down.position
                var triggered = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - startPos.x
                    val dy = change.position.y - startPos.y
                    if (!triggered && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.15f) {
                        val shouldEnter = if (isBarBottom) dy > swipeThresholdPx else (dy > swipeThresholdPx || dy < -swipeThresholdPx)
                        if (shouldEnter) {
                            triggered = true
                            change.consume()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnterImmersiveFullscreen()
                            break
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(outerBarColor)
            .then(modifier)
            .then(swipeFullscreenModifier)
            .height(barHeight)
            .padding(horizontal = if (isLandscape) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(pillHeight)
                .clip(pillShape)
                .background(pillBackground)
                .border(1.dp, pillBorderColor, pillShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillHeight)
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Userscripts Button (clean icon without overlapping numeric badge)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onScriptMenuClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_code),
                        contentDescription = "Userscripts",
                        tint = if (scriptMatchCount > 0 || isCaptureActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Security Shield or Search Engine selector
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { if (iconState == 0) onSearchEngineClick() else onLockClick() },
                    contentAlignment = Alignment.Center
                ) {
                    when (iconState) {
                        1 -> Icon(
                            painterResource(R.drawable.uh_ic_shield_checkmark_24),
                            contentDescription = "Secure",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        2 -> Icon(
                            painterResource(R.drawable.uh_ic_shield_slash_24),
                            contentDescription = "Not Secure",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        else -> AsyncImage(
                            model = searchEngine.iconUrl,
                            contentDescription = searchEngine.name,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                        )
                    }
                    if (iconState == 0) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_triangle_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(9.dp)
                                .align(Alignment.BottomEnd)
                                .padding(end = 2.dp, bottom = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                // URL Text or Editable Input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(pillHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isEditing) {
                        val cursorAccent = MaterialTheme.colorScheme.primary
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides TextSelectionColors(
                                handleColor = cursorAccent,
                                backgroundColor = cursorAccent.copy(alpha = 0.28f)
                            )
                        ) {
                            BasicTextField(
                                state = textState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 28.dp)
                                    .focusRequester(focusRequester)
                                    .background(Color.Transparent),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                                cursorBrush = SolidColor(cursorAccent),
                                lineLimits = TextFieldLineLimits.SingleLine,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                onKeyboardAction = KeyboardActionHandler {
                                    if (textState.text.isNotBlank()) onSubmit(textState.text.toString())
                                },
                                decorator = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(pillHeight),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (textState.text.isEmpty()) {
                                            Text(
                                                "Search or type URL",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        if (textState.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    textState.edit {
                                        replace(0, length, "")
                                        placeCursorAtEnd()
                                    }
                                    onClear()
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close),
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (url.isBlank() || url == "about:blank") "Search or type URL" else formatUrl(url),
                            color = if (url.isBlank() || url == "about:blank") {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onEditStart() }
                        )
                    }
                }

                // Inline Dynamic Mode Badge + Reader Mode + Reload/Stop when browsing
                if (!isEditing && hasPageUrl) {
                    if (isDesktopMode || zoomPercent != 100) {
                        val badgeLabel = buildString {
                            if (isDesktopMode) append("PC")
                            if (zoomPercent != 100) {
                                if (isNotEmpty()) append("·")
                                append("$zoomPercent%")
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .clickable { onModeBadgeClick() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isReaderEligible || isReaderActive) {
                        Box(
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isReaderActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else Color.Transparent
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onReaderClick
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_reader_mode_24),
                                contentDescription = if (isReaderActive) "Exit Reader Mode" else "Enter Reader Mode",
                                tint = if (isReaderActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onReloadStopClick,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isLoading) R.drawable.fx_ic_close_24 else R.drawable.uh_ic_reload_24),
                            contentDescription = if (isLoading) "Stop" else "Reload",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Tab Counter Badge (identical normal theme colors across Normal & Private)
                val tabBadgeColor = MaterialTheme.colorScheme.onSurface
                Box {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(23.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.8.dp, tabBadgeColor, RoundedCornerShape(7.dp))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTabCountClick,
                                onLongClick = {
                                    if (!isEditing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showTabQuickMenu = true
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (tabCount > 99) "∞" else "$tabCount",
                            color = tabBadgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    DropdownMenu(
                        expanded = showTabQuickMenu,
                        onDismissRequest = { showTabQuickMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("New tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showTabQuickMenu = false
                                onQuickNewTab()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New private tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                                    contentDescription = null,
                                    tint = Color(0xFFA78BFA),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showTabQuickMenu = false
                                onQuickNewPrivateTab()
                            }
                        )
                        if (hasPageUrl) {
                            DropdownMenuItem(
                                text = { Text("Duplicate tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTabQuickMenu = false
                                    onQuickDuplicateTab()
                                }
                            )
                        }
                        if (!isPrivate && tabCount > 0) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isCurrentTabPinned) "Unpin tab" else "Pin tab",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_pin_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTabQuickMenu = false
                                    onQuickTogglePinTab()
                                }
                            )
                        }
                        if (hasPageUrl) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isCurrentTabMuted) "Unmute tab" else "Mute tab",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isCurrentTabMuted) R.drawable.uh_ic_volume_on_24 else R.drawable.uh_ic_volume_off_24
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTabQuickMenu = false
                                    onQuickToggleMuteTab()
                                }
                            )
                        }
                        if (tabCount > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                            DropdownMenuItem(
                                text = { Text("Close current tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTabQuickMenu = false
                                    onQuickCloseCurrentTab()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isPrivate) "Close all private tabs" else "Close all tabs",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTabQuickMenu = false
                                    onQuickCloseAllTabs()
                                }
                            )
                        }
                    }
                }

                // 3-Dot Menu
                IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu),
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (!isEditing && isLoading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}
