package com.naarni.service.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.screens.AlertDetailScreen
import com.naarni.service.ui.screens.AlertsScreen
import com.naarni.service.ui.screens.CreateJobCardScreen
import com.naarni.service.ui.screens.TicketDetailScreen
import com.naarni.service.ui.screens.HomeScreen
import com.naarni.service.ui.screens.JobCardDetailScreen
import com.naarni.service.ui.screens.JobCardsScreen
import com.naarni.service.ui.screens.NotificationsScreen
import com.naarni.service.ui.screens.ProfileScreen
import com.naarni.service.ui.screens.TicketsScreen
import com.naarni.service.ui.screens.VehicleDetailScreen
import com.naarni.service.ui.screens.VehiclesScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    JobCards("jobcards", "Jobs", Icons.AutoMirrored.Filled.Assignment),
    Fleet("fleet", "Fleet", Icons.Default.DirectionsBus),
    Alerts("alerts", "Alerts", Icons.Default.Notifications),
    Tickets("tickets", "Tickets", Icons.Default.ConfirmationNumber),
    Profile("profile", "Profile", Icons.Default.Person),
}

@Composable
fun MainShell(vm: AppViewModel) {
    val nav = rememberNavController()
    val tabs = Tab.entries

    // Consume a deep link from a tapped notification (naarni://alert|ticket|jobcard|vehicle/{id}).
    LaunchedEffect(DeepLinkBus.pending) {
        val link = DeepLinkBus.pending ?: return@LaunchedEffect
        DeepLinkBus.pending = null
        val uri = runCatching { android.net.Uri.parse(link) }.getOrNull() ?: return@LaunchedEffect
        val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val route = when (uri.host) {
            "alert" -> "alert/$id"
            "ticket" -> "ticket/$id"
            "jobcard" -> "jobcard/$id"
            "vehicle" -> "vehicle/$id"
            else -> return@LaunchedEffect
        }
        runCatching { nav.navigate(route) { launchSingleTop = true } }
    }

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
            composable(Tab.Home.route) {
                HomeScreen(
                    vm = vm,
                    onCreateJobCard = { nav.navigate("create") },
                    onOpenNotifications = { nav.navigate("notifications") },
                    onOpenJobCard = { name -> nav.navigate("jobcard/$name") },
                )
            }
            composable("notifications") {
                NotificationsScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenJobCard = { name -> nav.navigate("jobcard/$name") },
                )
            }
            composable("jobcard/{name}") { entry ->
                JobCardDetailScreen(
                    vm,
                    jobCard = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Tab.JobCards.route) {
                JobCardsScreen(vm, onOpenJobCard = { name -> nav.navigate("jobcard/$name") })
            }
            composable(Tab.Fleet.route) {
                VehiclesScreen(vm, onOpenVehicle = { name -> nav.navigate("vehicle/$name") })
            }
            composable("vehicle/{name}") { entry ->
                VehicleDetailScreen(
                    vm,
                    vehicle = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Tab.Alerts.route) {
                AlertsScreen(vm, onOpenAlert = { name -> nav.navigate("alert/$name") })
            }
            composable("alert/{name}") { entry ->
                AlertDetailScreen(
                    vm,
                    alertName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenTicket = { name -> nav.navigate("ticket/$name") },
                    onOpenJobCard = { name -> nav.navigate("jobcard/$name") },
                    onOpenVehicle = { name -> nav.navigate("vehicle/$name") },
                )
            }
            composable(Tab.Tickets.route) {
                TicketsScreen(vm, onOpenTicket = { name -> nav.navigate("ticket/$name") })
            }
            composable("ticket/{name}") { entry ->
                TicketDetailScreen(
                    vm,
                    ticketName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenJobCard = { name -> nav.navigate("jobcard/$name") },
                    onOpenVehicle = { name -> nav.navigate("vehicle/$name") },
                )
            }
            composable(Tab.Profile.route) { ProfileScreen(vm) }
            composable("create") {
                CreateJobCardScreen(
                    vm,
                    onDone = {
                        // Land on the Jobs tab so the freshly-created card is visible
                        // immediately (JobCardsScreen reloads on entry).
                        nav.navigate(Tab.JobCards.route) {
                            popUpTo(Tab.Home.route) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        // Return to wherever they came from (Home); fall back to Home tab.
                        if (!nav.popBackStack()) {
                            nav.navigate(Tab.Home.route) { launchSingleTop = true }
                        }
                    },
                )
            }
        }
    }
}
