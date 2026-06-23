package com.naarni.service.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.screens.AlertsScreen
import com.naarni.service.ui.screens.CreateJobCardScreen
import com.naarni.service.ui.screens.HomeScreen
import com.naarni.service.ui.screens.JobCardsScreen
import com.naarni.service.ui.screens.ProfileScreen
import com.naarni.service.ui.screens.TicketsScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    JobCards("jobcards", "Jobs", Icons.AutoMirrored.Filled.Assignment),
    Alerts("alerts", "Alerts", Icons.Default.Notifications),
    Tickets("tickets", "Tickets", Icons.Default.ConfirmationNumber),
    Profile("profile", "Profile", Icons.Default.Person),
}

@Composable
fun MainShell(vm: AppViewModel) {
    val nav = rememberNavController()
    val tabs = Tab.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                launchSingleTop = true
                                popUpTo(Tab.Home.route) { saveState = true }
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Home.route) { HomeScreen(vm = vm, onCreateJobCard = { nav.navigate("create") }) }
            composable(Tab.JobCards.route) { JobCardsScreen(vm) }
            composable(Tab.Alerts.route) { AlertsScreen(vm) }
            composable(Tab.Tickets.route) { TicketsScreen(vm) }
            composable(Tab.Profile.route) { ProfileScreen(vm) }
            composable("create") { CreateJobCardScreen(vm, onDone = { nav.popBackStack() }) }
        }
    }
}
