package app.harvester.companion.browser.toolbar

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
    zoomPercent: Int = 100,
    onEditTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditStart: () -> Unit,
    onClear: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onLockClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onModeBadgeClick: () -> Unit = {},
    onReloadStopClick: () -> Unit = {},
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    searchEngine: SearchEnginePrefs.Entry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val barHeight = if (isLandscape) 50.dp else 58.dp
    val pillHeight = if (isLandscape) 40.dp else 46.dp
    val showFullUrl = SettingsPrefs.getBool(context, SettingsPrefs.K_SHOW_FULL_URL, false)
    val keyboardController = LocalSoftwareKeyboardController.current

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(outerBarColor)
            .padding(horizontal = if (isLandscape) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(pillHeight)
                .clip(pillShape)
                .background(pillBackground)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), pillShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillHeight)
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scripts Button with live match badge / capture indicator
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
                        contentDescription = "Scripts",
                        tint = if (scriptMatchCount > 0 || isCaptureActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    if (scriptMatchCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-1).dp, y = 2.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = scriptMatchCount.coerceAtMost(9).toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (isCaptureActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-3).dp, y = 4.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4ADE80))
                        )
                    }
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
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides TextSelectionColors(
                                handleColor = Color.Transparent,
                                backgroundColor = Color.Transparent
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

                // Inline Dynamic Mode Badge + Reload/Stop when browsing
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

                // Tab Counter Badge
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(23.dp)
                        .border(1.8.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabCountClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (tabCount > 99) "∞" else "$tabCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
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
