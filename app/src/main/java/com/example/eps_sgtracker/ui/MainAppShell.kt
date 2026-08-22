package com.example.eps_sgtracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Switches to a bottom-navigation tab without growing the back stack.
 *
 * `launchSingleTop` alone was not enough: it only stops the SAME destination being duplicated at
 * the top, so every switch to a DIFFERENT tab still pushed an entry. SETUP -> PLAN -> TRACK ->
 * SETUP left four of them, and Back then walked the entire visit history instead of leaving the
 * app. Popping back to the start destination keeps the stack one deep.
 *
 * `saveState`/`restoreState` are the other half: without them, returning to a tab built a brand new
 * entry, so each tab silently lost its scroll position and UI state every time it was left.
 */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MainAppShell(viewModel: TrackerViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Fullscreen is a 3D-view-only mode, but the nav bar it hides lives out here - so the flag has
    // to be read at this level rather than inside Satellite3DView.
    val is3DFullscreen by viewModel.is3DFullscreen.collectAsStateWithLifecycle()

    // Safety net against a chrome-less dead end: nothing should be able to leave fullscreen set
    // while the user is on another tab, but if anything ever did, the nav bar would be gone with no
    // control on screen to bring it back. Clearing it on any route change makes that unreachable.
    LaunchedEffect(currentRoute) {
        if (currentRoute != "view" && is3DFullscreen) viewModel.set3DFullscreen(false)
    }

    Scaffold(
        // The 3D view paints its own black background, but the Scaffold's container is what shows
        // through in the status-bar strip above it (edge-to-edge plus the systemBars inset) and
        // around the nav bar. Left at the theme's background those two near-blacks met in a visible
        // seam, so this screen - and only this screen - gets a black container to match.
        containerColor = if (currentRoute == "view") Color.Black else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!is3DFullscreen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    // Each onClick is guarded against re-navigating to the already-current route:
                    // launchSingleTop alone doesn't make that a no-op - it prevents a *duplicate*
                    // back-stack entry, but still replaces the top entry with a fresh instance, and
                    // NavHost's transition keys on the entry, so tapping the active tab replayed the
                    // enter animation for no reason.
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Setup") },
                        label = { Text("SETUP") },
                        selected = currentRoute == "setup",
                        onClick = { if (currentRoute != "setup") navController.navigateToTab("setup") }
                    )
                    // Ordered by planning horizon: PLAN looks days ahead, TRACK is the live
                    // now-to-next-few-hours view.
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Plan") },
                        label = { Text("PLAN") },
                        selected = currentRoute == "plan",
                        onClick = { if (currentRoute != "plan") navController.navigateToTab("plan") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.MonitorHeart, contentDescription = "Track") },
                        label = { Text("TRACK") },
                        selected = currentRoute == "pass_list",
                        onClick = { if (currentRoute != "pass_list") navController.navigateToTab("pass_list") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Language, contentDescription = "View") },
                        label = { Text("VIEW") },
                        selected = currentRoute == "view",
                        onClick = { if (currentRoute != "view") navController.navigateToTab("view") }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "pass_list",
            // Dropping the inset in fullscreen is what actually lets the globe reach the bottom of
            // the display - Scaffold still reports padding for the (now absent) bottom bar.
            modifier = if (is3DFullscreen) Modifier else Modifier.padding(innerPadding)
        ) {
            composable("setup") { SetupScreen(viewModel) }
            composable("pass_list") { PassListScreen(viewModel) }
            composable("plan") { PassForecastScreen(viewModel) }
            composable("view") {
                Satellite3DView(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}