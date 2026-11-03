package app.harvester.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController

@Composable
fun StartupFragment(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.navigate("home") {
            popUpTo("startup") { inclusive = true }
        }
    }
}
