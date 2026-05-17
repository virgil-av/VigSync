package com.vigsync.feature.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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
import com.vigsync.feature.ui.screens.*

sealed class Screen(val route: String, val baseRoute: String, val label: String, val icon: @Composable () -> Unit) {
    object Dashboard : Screen("dashboard", "dashboard", "Home", { Icon(Icons.Default.Home, contentDescription = null) })
    object Events : Screen("events", "events", "Events", { Icon(Icons.Default.List, contentDescription = null) })
    object Settings : Screen("settings", "settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VigSyncApp() {
    val navController = rememberNavController()
    val items = listOf(Screen.Dashboard, Screen.Events, Screen.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    val isSelected = currentDestination?.hierarchy?.any { 
                        it.route == screen.route 
                    } == true
                    
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.label) },
                        selected = isSelected,
                        onClick = {
                            navController.navigate(screen.route) {
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
                DashboardScreen(navController = navController) 
            }
            composable(Screen.Events.route) {
                EventsScreen()
            }
            composable(
                route = Screen.Settings.route + "?openMqttConfig={openMqttConfig}",
                arguments = listOf(
                    navArgument("openMqttConfig") { 
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val openMqttConfig = backStackEntry.arguments?.getBoolean("openMqttConfig") ?: false
                SettingsScreen(openMqttConfig = openMqttConfig)
            }
        }
    }
}
