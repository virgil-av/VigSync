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
import androidx.compose.material.icons.filled.*
import com.vigsync.feature.ui.screens.*

sealed class Screen(val route: String, val label: String, val icon: @Composable () -> Unit) {
    object Dashboard : Screen("dashboard", "Home", { Icon(Icons.Default.Home, contentDescription = null) })
    object Events : Screen("events", "Events", { Icon(Icons.Default.List, contentDescription = null) })
    object Pairing : Screen("pairing", "Pair", { Icon(Icons.Default.QrCode, contentDescription = null) })
    object Debug : Screen("debug", "Debug", { Icon(Icons.Default.BugReport, contentDescription = null) })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VigSyncApp() {
    val navController = rememberNavController()
    // Pairing is now accessed from Dashboard top bar
    val items = listOf(Screen.Dashboard, Screen.Events, Screen.Debug, Screen.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Dashboard.route, Modifier.padding(innerPadding)) {
            composable(Screen.Dashboard.route) { 
                DashboardScreen(
                    onNavigateToPairing = { navController.navigate(Screen.Pairing.route) }
                ) 
            }
            composable(Screen.Events.route) { EventsScreen() }
            composable(Screen.Pairing.route) { 
                PairingScreen(
                    onPairingSuccess = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Dashboard.route) { inclusive = true }
                        }
                    }
                ) 
            }
            composable(Screen.Debug.route) { DebugScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
