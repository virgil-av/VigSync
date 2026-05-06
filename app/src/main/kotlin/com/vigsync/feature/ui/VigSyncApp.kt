package com.vigsync.feature.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.compose.material.icons.filled.*
import com.vigsync.feature.ui.screens.*

sealed class Screen(val route: String, val baseRoute: String, val label: String, val icon: @Composable () -> Unit) {
    object Dashboard : Screen("dashboard", "dashboard", "Home", { Icon(Icons.Default.Home, contentDescription = null) })
    object Events : Screen("events?deviceName={deviceName}", "events", "Events", { Icon(Icons.Default.List, contentDescription = null) }) {
        fun createRoute(deviceName: String? = null) = if (deviceName != null) "events?deviceName=$deviceName" else "events"
    }
    object Pairing : Screen("pairing?startScanner={startScanner}", "pairing", "Pair", { Icon(Icons.Default.QrCode, contentDescription = null) }) {
        fun createRoute(startScanner: Boolean) = "pairing?startScanner=$startScanner"
    }
    object Debug : Screen("debug", "debug", "Debug", { Icon(Icons.Default.BugReport, contentDescription = null) })
    object Settings : Screen("settings", "settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VigSyncApp() {
    val navController = rememberNavController()
    // Pairing is now accessed from Dashboard top bar
    val items = listOf(Screen.Dashboard, Screen.Events, Screen.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    // Match by base route to handle parameters correctly
                    val isSelected = currentDestination?.hierarchy?.any { 
                        it.route?.substringBefore("?") == screen.baseRoute 
                    } == true
                    
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.label) },
                        selected = isSelected,
                        onClick = {
                            // If we are already on this screen (even with different params), 
                            // navigating to the baseRoute will clear them.
                            navController.navigate(screen.baseRoute) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                // on the back stack as users select items
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination when
                                // reselecting the same item
                                launchSingleTop = true
                                // Restore state when reselecting a previously selected item
                                // But NOT for Events to ensure it resets to All Devices
                                if (!isSelected && screen != Screen.Events) {
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Dashboard.route, Modifier.padding(innerPadding)) {
            composable(Screen.Dashboard.route) { 
                DashboardScreen(navController = navController) 
            }
            composable(
                route = Screen.Events.route,
                arguments = listOf(navArgument("deviceName") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) {
                EventsScreen()
            }
            composable(Screen.Debug.route) { DebugScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
