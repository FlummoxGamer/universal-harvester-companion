package app.harvester.companion.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

/**
 * Minimal dark obsidian Private Home screen shown whenever a new Private tab is opened.
 * Features the custom Private mask crest, a clean quick-search pill, and a one-tap
 * Clear Private Session control.
 */
@Composable
fun PrivateHomeScreen(
    privateTabCount: Int,
    hasTempSearches: Boolean,
    onSearchClick: () -> Unit,
    onClearPrivateSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgObsidian = Color(0xFF090712)
    val violetGlow = Color(0xFF7C3AED)
    val violetSoft = Color(0xFFB794F4)
    val cardSurface = Color(0xFF141024)
    val subtleBorder = Color(0xFF2D2448)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgObsidian)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        violetGlow.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    radius = 950f
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Sleek Private Mask Crest
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                violetGlow.copy(alpha = 0.28f),
                                cardSurface
                            )
                        )
                    )
                    .border(1.2.dp, violetSoft.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                    contentDescription = "Private Browsing",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Private Browsing",
                color = Color(0xFFF4F1FF),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Cookies, site data, and history stay isolated and vanish when you close your private tabs.",
                color = Color(0xFFA79EC2),
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Quick Search Pill
            Surface(
                shape = CircleShape,
                color = cardSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .border(1.dp, subtleBorder, CircleShape)
                    .clickable(onClick = onSearchClick)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_search_24),
                        contentDescription = "Search",
                        tint = violetSoft,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search privately or enter URL",
                        color = Color(0xFF9F96B8),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // One-tap Clear Private Session action when Private tabs or temp searches exist
            if (privateTabCount > 0 || hasTempSearches) {
                Spacer(modifier = Modifier.height(22.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFF1F1634),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, Color(0xFFF87171).copy(alpha = 0.28f), RoundedCornerShape(999.dp))
                        .clickable(onClick = onClearPrivateSession)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_delete_24),
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Clear Private Session",
                            color = Color(0xFFFCA5A5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
