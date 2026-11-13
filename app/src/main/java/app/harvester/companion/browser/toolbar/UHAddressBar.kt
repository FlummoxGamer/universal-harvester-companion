package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs

@Composable
fun UHAddressBar(
    url: String,
    isEditing: Boolean,
    tabCount: Int,
    iconState: Int,
    isWallpaperActive: Boolean,
    editText: String,
    onEditTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditStart: () -> Unit,
    onClear: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bottomBarColor = if (isWallpaperActive) Color.Transparent else colorResource(id = R.color.bg)
    val searchEngineEntry = remember(context) { SearchEnginePrefs.currentEntry(ctx = context) }
    val showFullUrl = SettingsPrefs.getBool(context, SettingsPrefs.K_SHOW_FULL_URL, false)
    
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

    LaunchedEffect(textState.text) {
        onEditTextChanged(textState.text.toString())
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(bottomBarColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colorResource(id = R.color.surface_2))
                .border(1.dp, colorResource(id = R.color.border), RoundedCornerShape(24.dp))
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onScriptMenuClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_code),
                    contentDescription = "Scripts",
                    tint = colorResource(id = R.color.text),
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSearchEngineClick, modifier = Modifier.size(24.dp)) {
                    when (iconState) {
                        1 -> Icon(painterResource(R.drawable.ic_lock), contentDescription = "Lock", tint = colorResource(id = R.color.accent), modifier = Modifier.size(24.dp).clickable { onSearchEngineClick() })
                        2 -> Icon(painterResource(R.drawable.ic_lock_open), contentDescription = "Open Lock", tint = colorResource(id = R.color.red), modifier = Modifier.size(24.dp).clickable { onSearchEngineClick() })
                        else -> {
                            AsyncImage(
                                model = searchEngineEntry.iconUrl,
                                contentDescription = "Search Engine",
                                modifier = Modifier.size(24.dp).clickable { onSearchEngineClick() }
                            )
                        }
                    }
                }
                if (iconState == 0) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_triangle_down),
                        contentDescription = null,
                        tint = colorResource(id = R.color.text),
                        modifier = Modifier.size(10.dp).align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp)
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f).height(52.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isEditing) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides androidx.compose.foundation.text.selection.TextSelectionColors(
                            handleColor = Color.Transparent,
                            backgroundColor = Color.Transparent
                        )
                    ) {
                        BasicTextField(
                            state = textState,
                            modifier = Modifier.fillMaxWidth().padding(end = 28.dp).focusRequester(focusRequester).background(Color.Transparent),
                            textStyle = TextStyle(color = colorResource(id = R.color.text), fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            onKeyboardAction = KeyboardActionHandler {
                                if (textState.text.isNotBlank()) onSubmit(textState.text.toString())
                            },
                            decorator = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (textState.text.isEmpty()) {
                                        Text("Search or type URL", color = colorResource(id = R.color.subtext), fontSize = 14.sp)
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
                            modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = "Clear",
                                tint = colorResource(id = R.color.subtext),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (url.isBlank() || url == "about:blank") "Search or type URL" else formatUrl(url),
                        color = if (url.isBlank() || url == "about:blank") colorResource(id = R.color.subtext) else colorResource(id = R.color.text),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp).clickable { onEditStart() }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(24.dp)
                    .border(2.dp, colorResource(id = R.color.text), RoundedCornerShape(8.dp))
                    .clickable { onTabCountClick() },
                contentAlignment = Alignment.Center
            ) {
                Text("$tabCount", color = colorResource(id = R.color.text), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu),
                    contentDescription = "Menu",
                    tint = colorResource(id = R.color.text),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
