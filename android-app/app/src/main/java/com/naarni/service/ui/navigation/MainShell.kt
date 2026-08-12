package com.naarni.service.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.ui.chat.ChatLifecycle
import com.naarni.service.ui.chat.ChatGalleryScreen
import com.naarni.service.ui.chat.ChatListScreen
import com.naarni.service.ui.chat.ChatThreadScreen
import com.naarni.service.ui.chat.ChatViewModel
import com.naarni.service.ui.chat.NewChatScreen
import androidx.compose.material.icons.filled.BatteryChargingFull
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.screens.AlertDetailScreen
import com.naarni.service.ui.screens.AlertsScreen
import com.naarni.service.ui.screens.CreateJobCardScreen
import com.naarni.service.ui.screens.DutyScreen
import com.naarni.service.ui.screens.TicketDetailScreen
import com.naarni.service.ui.screens.HomeScreen
import com.naarni.service.ui.screens.JobCardDetailScreen
import com.naarni.service.ui.screens.JobCardsScreen
import com.naarni.service.ui.screens.NotificationsScreen
import com.naarni.service.ui.screens.ProcessListScreen
import com.naarni.service.ui.screens.ProcessRunnerScreen
import com.naarni.service.ui.screens.ProcessStartScreen
import com.naarni.service.ui.screens.ProfileScreen
import com.naarni.service.ui.screens.TicketsScreen
import com.naarni.service.ui.screens.VehicleDetailScreen
import com.naarni.service.ui.screens.VehiclesScreen

/**
 * Bottom-nav destinations.
 *
 * Trimmed to Home, Alerts and Battery for this release. Jobs, Fleet and Tickets
 * still exist as routes below — deep links from notifications keep working, and
 * the Home screen can still open a job card — they simply have no tab. Profile
 * stays because logout, the depot picker and account deletion live there.
 *
 * [Battery] points at the generic process engine; it is called Battery because
 * that is the only published process today, and renaming it is a one-line change
 * when a second process ships.
 */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    Alerts("alerts", "Alerts", Icons.Default.Notifications),
    Battery("processes", "Battery", Icons.Default.BatteryChargingFull),
    Profile("profile", "Profile", Icons.Default.Person),
}

/** Routes that still exist for deep links but are no longer tabs. */
private object HiddenRoute {
    const val JOB_CARDS = "jobcards"
    const val FLEET = "fleet"
    const val TICKETS = "tickets"
}

@Composable
fun MainShell(vm: AppViewModel) {
    val nav = rememberNavController()
    val tabs = Tab.entries

    // Hoisted to the shell so the tab badge stays live regardless of which tab is
    // showing, and so the socket is owned by the shell rather than by a screen.
    val chatVm: ChatViewModel = viewModel()
    val unreadChats by chatVm.unreadTotal.collectAsStateWithLifecycle()
    ChatLifecycle(chatVm)

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
            // naarni://chat/{room}?msg={seq} — from a chat push notification.
            "chat" -> "thread/$id"
            else -> return@LaunchedEffect
        }
        runCatching { nav.navigate(route) { launchSingleTop = true } }
    }

    // Top-level tab routes show the bottom nav + status bar; detail/sub pages hide
    // both for a focused, full-height view (fixes the double-footer + whitespace).
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val isTopLevel = current == null ||
        tabs.any { it.route == current } ||
        current in setOf(HiddenRoute.JOB_CARDS, HiddenRoute.FLEET, HiddenRoute.TICKETS)

    val view = LocalView.current
    LaunchedEffect(isTopLevel) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isTopLevel) controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
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
                            icon = {
                                // Badge reads a single Room-backed Flow, so it is
                                // correct offline and on a cold start with no network.
                                if (tab == Tab.Chat && unreadChats > 0) {
                                    BadgedBox(badge = {
                                        Badge { Text(if (unreadChats > 99) "99+" else "$unreadChats") }
                                    }) { Icon(tab.icon, contentDescription = tab.label) }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
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
                    onOpenDuty = { nav.navigate("duty") },
                )
            }

            // Roster + attendance history. Reached from the duty card rather than
            // a tab: checking in is a daily action, reviewing the roster is weekly,
            // and the nav bar is already at its useful limit.
            composable("duty") { DutyScreen(vm, onBack = { nav.popBackStack() }) }
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
            composable(HiddenRoute.JOB_CARDS) {
                JobCardsScreen(vm, onOpenJobCard = { name -> nav.navigate("jobcard/$name") })
            }
            composable(HiddenRoute.FLEET) {
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
                AlertsScreen(vm, onOpenGroup = { key -> nav.navigate("alertgroup/${android.net.Uri.encode(key)}") })
            }
            composable("alertgroup/{key}") { entry ->
                AlertDetailScreen(
                    vm,
                    dedupKey = android.net.Uri.decode(entry.arguments?.getString("key").orEmpty()),
                    onBack = { nav.popBackStack() },
                    onOpenTicket = { name -> nav.navigate("ticket/$name") },
                    onOpenVehicle = { name -> nav.navigate("vehicle/$name") },
                )
            }
            composable("alert/{name}") { entry ->
                // Notification deep link (naarni://alert/{alert_event}) → resolve to its group.
                AlertDetailScreen(
                    vm,
                    alertEvent = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenTicket = { name -> nav.navigate("ticket/$name") },
                    onOpenVehicle = { name -> nav.navigate("vehicle/$name") },
                )
            }
            composable(HiddenRoute.TICKETS) {
                TicketsScreen(vm, onOpenTicket = { name -> nav.navigate("ticket/$name") })
            }

            // ---- Process engine: one list, one start screen, one generic runner.
            composable(Tab.Battery.route) {
                ProcessListScreen(
                    vm,
                    onOpenProcess = { family -> nav.navigate("process/$family") },
                    onResumeRun = { run -> nav.navigate("run/$run") },
                )
            }
            composable("process/{family}") { entry ->
                ProcessStartScreen(
                    vm,
                    processFamily = entry.arguments?.getString("family").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onRunStarted = { run ->
                        nav.navigate("run/$run") {
                            // Drop the start screen so Back returns to the list,
                            // not into a run the operator already began.
                            popUpTo(Tab.Battery.route)
                        }
                    },
                )
            }
            composable("run/{name}") { entry ->
                ProcessRunnerScreen(
                    vm,
                    runName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onFinished = {
                        nav.navigate(Tab.Battery.route) {
                            popUpTo(Tab.Battery.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
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
            // ---- Chat: list + one generic thread screen.
            composable(Tab.Chat.route) {
                ChatListScreen(
                    chatVm,
                    onOpenRoom = { room -> nav.navigate("thread/$room") },
                    onNewChat = { nav.navigate("newchat") },
                )
            }
            composable("newchat") {
                NewChatScreen(
                    chatVm,
                    onBack = { nav.popBackStack() },
                    onOpenRoom = { room ->
                        // Replace the picker in the back stack: coming back from a
                        // thread should land on the chat list, not the directory.
                        nav.navigate("thread/$room") { popUpTo("newchat") { inclusive = true } }
                    },
                )
            }
            composable("thread/{room}") { entry ->
                val room = entry.arguments?.getString("room").orEmpty()
                ChatThreadScreen(
                    vm = chatVm,
                    roomName = room,
                    onBack = { nav.popBackStack() },
                    // A field observation becomes a Service Ticket without leaving
                    // the thread — the reason chat lives in this app at all.
                    onRaiseTicket = { msg -> nav.navigate("ticket/${msg.ticket ?: ""}") },
                    onOpenGallery = { nav.navigate("gallery/$room") },
                )
            }
            composable("gallery/{room}") { entry ->
                ChatGalleryScreen(
                    vm = chatVm,
                    roomName = entry.arguments?.getString("room").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Tab.Profile.route) { ProfileScreen(vm) }
            composable("create") {
                CreateJobCardScreen(
                    vm,
                    onDone = {
                        // Land on the Jobs tab so the freshly-created card is visible
                        // immediately (JobCardsScreen reloads on entry).
                        nav.navigate(HiddenRoute.JOB_CARDS) {
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
