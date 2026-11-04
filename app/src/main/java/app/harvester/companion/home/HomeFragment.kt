package app.harvester.companion.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import java.io.File

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = LocalContext.current
                val shortcuts = remember { ShortcutStore.list(context) }
                val wallpaperPath = remember { WallpaperStore.getCurrentWallpaper(context) }
                val hasWallpaper = wallpaperPath != null && wallpaperPath != "default"
                val textColor = if (hasWallpaper) Color.White else Color(0xFF1D3557)

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0F1A))) {
                    if (hasWallpaper) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(wallpaperPath)).build(),
                            contentDescription = "Wallpaper",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(0.3f))
                        
                        Text(
                            text = "Universal Harvester",
                            color = if (hasWallpaper) textColor else Color(0xFF457B9D),
                            fontSize = 48.sp,
                            fontFamily = FontFamily(Font(R.font.logo_font))
                        )
                        
                        Spacer(modifier = Modifier.height(36.dp))

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(shortcuts) { shortcut ->
                                ShortcutItem(shortcut, hasWallpaper, onOpen = {
                                    findNavController().navigate("browser")
                                })
                            }
                            item {
                                AddShortcutItem(textColor)
                            }
                        }
                        
                        Spacer(modifier = Modifier.weight(0.1f))
                    }
                }
            }
        }
    }
}

@Composable
fun ShortcutItem(shortcut: Shortcut, hasWallpaper: Boolean, onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onOpen() }) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFFE9EDF2), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = shortcut.iconUrl,
                contentDescription = shortcut.name,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = shortcut.name,
            color = if (hasWallpaper) Color.White else Color(0xFF1D3557),
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
fun AddShortcutItem(textColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFFE9EDF2), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = "Add",
                tint = Color(0xFF457B9D)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Add", color = textColor, fontSize = 12.sp)
    }
}
