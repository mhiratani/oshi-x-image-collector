package com.hilamalu.oshixcollector.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.ui.accounts.AccountsScreen
import com.hilamalu.oshixcollector.ui.media.MediaListScreen
import com.hilamalu.oshixcollector.ui.onboarding.OnboardingScreen
import com.hilamalu.oshixcollector.ui.settings.SettingsScreen

private const val START_ROUTE = "start"

private sealed class Destination(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Media : Destination("media", R.string.nav_media, Icons.Filled.Photo)
    data object Accounts : Destination("accounts", R.string.nav_accounts, Icons.Filled.AccountCircle)
    data object Settings : Destination("settings", R.string.nav_settings, Icons.Filled.Settings)
}

private val bottomNavDestinations = listOf(Destination.Media, Destination.Accounts, Destination.Settings)

@Composable
fun OshiXImageCollectorNavGraph(navController: NavHostController = rememberNavController()) {
    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            if (currentRoute != START_ROUTE) {
                NavigationBar {
                    bottomNavDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = START_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(START_ROUTE) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Destination.Media.route) {
                            popUpTo(START_ROUTE) { inclusive = true }
                        }
                    },
                    onNeedsConfiguration = {
                        navController.navigate(Destination.Settings.route) {
                            popUpTo(START_ROUTE) { inclusive = true }
                        }
                    }
                )
            }
            composable(Destination.Media.route) { MediaListScreen() }
            composable(Destination.Accounts.route) { AccountsScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
