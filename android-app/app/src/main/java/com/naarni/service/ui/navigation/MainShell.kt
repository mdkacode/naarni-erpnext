package com.naarni.service.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.Chat
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
import com.naarni.service.ui.chat.NewGroupScreen
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.components.HairlineDivider
import com.naarni.service.ui.theme.AppSurface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naarni.service.appContainer
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.screens.AlertDetailScreen
import com.naarni.service.ui.screens.AlertsScreen
import com.naarni.service.ui.screens.CreateJobCardScreen
import com.naarni.service.ui.screens.DutyScreen
import com.naarni.service.ui.screens.TicketDetailScreen
import com.naarni.service.ui.screens.HomeScreen
import com.naarni.service.ui.screens.JobCardDetailScreen
import com.naarni.service.ui.screens.JobCardsScreen
import com.naarni.service.ui.material.MaterialDetailScreen
import com.naarni.service.ui.material.GATE_STAGE
import com.naarni.service.ui.material.GateEntryScreen
import com.naarni.service.ui.material.MaterialListScreen
import com.naarni.service.ui.material.MaterialViewModel
import com.naarni.service.ui.screens.NotificationsScreen
import com.naarni.service.ui.screens.ProcessHistoryScreen
import com.naarni.service.ui.screens.ProcessBoardScreen
import com.naarni.service.ui.screens.ProcessListScreen
import com.naarni.service.ui.screens.ProcessRunReportScreen
import com.naarni.service.ui.screens.ProcessRunnerScreen
import com.naarni.service.ui.screens.ProcessStartScreen
import com.naarni.service.ui.screens.ProfileScreen
import com.naarni.service.ui.screens.TicketsScreen
import com.naarni.service.ui.screens.VehicleDetailScreen
import com.naarni.service.ui.screens.VehiclesScreen

/**
 * Bottom-nav destinations.
 *
 * Trimmed to Home, Alerts and Checks for this release. Jobs, Fleet and Tickets
 * still exist as routes below — deep links from notifications keep working, and
 * the Home screen can still open a job card — they simply have no tab. Profile
 * stays because logout, the depot picker and account deletion live there.
 *
 * [Checks] is the generic process engine. It was called "Battery" while battery
 * QC was the only published process, which stopped being true the moment a
 * second one could be authored — and a tab named after one process is a tab
 * nobody looks in for the others.
 *
 * "Checks" rather than "Processes" deliberately. A process is what an admin
 * authors; a check is what the person holding the phone actually does, and the
 * app already counts them that way everywhere else ("59 checks", "Not answered",
 * "Can't check this?"). The route has always been `processes` and stays that
 * way, so every existing deep link keeps working.
 */
enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /**
     * Roles that may see this tab. `null` means everyone signed in.
     *
     * This hides the tab; it does not secure it. The server decides what a user
     * may actually read or write — see `process_run.get_permission_query_conditions`
     * — and this exists so a technician who will never run an inspection is not
     * given a tab that answers every tap with a permission error.
     */
    val requiredRoles: Set<String>? = null,
) {
    Home("home", "Home", Icons.Rounded.Home),
    Chat("chat", "Chat", Icons.AutoMirrored.Rounded.Chat),
    Alerts("alerts", "Alerts", Icons.Rounded.Notifications),
    Checks(
        "processes",
        "Checks",
        Icons.Rounded.Checklist,
        requiredRoles = PROCESS_ROLES,
    ),
    /**
     * The material gate — inward and outward at Hubli and Narsapura.
     *
     * Role-gated like [Checks]: a technician who will never stand at a gate is
     * not given a tab that answers every tap with a permission error. This hides
     * the tab; the server decides what may actually be read or written — see
     * `material_movement.get_permission_query_conditions`.
     */
    Material(
        "material",
        "Material",
        Icons.Rounded.Inventory2,
        requiredRoles = MATERIAL_ROLES,
    ),
    Profile("profile", "Profile", Icons.Rounded.Person),
    ;

    fun isVisibleTo(roles: Set<String>): Boolean =
        requiredRoles == null || roles.any { it in requiredRoles }
}

/** Anyone who runs, verifies or oversees an inspection. */
private val PROCESS_ROLES = setOf(
    "Process Operator",
    "Process Author",
    "Process Verifier",
    "Process Viewer",
    "Battery QA Admin",
    "System Manager",
    "Administrator",
)

/** Anyone who records, verifies or oversees a material movement. */
private val MATERIAL_ROLES = setOf(
    "Material Gate Operator",
    "Material Supervisor",
    "Material Viewer",
    "Depot Manager",
    "System Manager",
    "Administrator",
)

/** Routes that still exist for deep links but are no longer tabs. */
private object HiddenRoute {
    const val JOB_CARDS = "jobcards"
    const val FLEET = "fleet"
    const val TICKETS = "tickets"
}

@Composable
fun MainShell(vm: AppViewModel) {
    val nav = rememberNavController()
    // Roles are stored at login; a signed-in user always has at least one, and a
    // stale set only costs a hidden tab, never access — the server is the gate.
    val roles = LocalContext.current.appContainer.session.roles
    val tabs = remember(roles) { Tab.entries.filter { it.isVisibleTo(roles) } }

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

    /**
     * Chat keeps the status bar.
     *
     * Hiding it suits a screen you look at once — a job card, a report. A
     * conversation is not that: people sit in it, and taking away the clock,
     * the battery and the signal bars for the whole time they are messaging is
     * a real cost for a strip of screen. Every messaging app keeps it, which is
     * also what makes its absence feel like a fault rather than a choice.
     */
    val isChatRoute = current != null && (
        current == Tab.Chat.route ||
            current.startsWith("thread/") ||
            current.startsWith("gallery/") ||
            current == "newchat" ||
            current == "newgroup"
        )

    val view = LocalView.current
    LaunchedEffect(isTopLevel, isChatRoute) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isTopLevel || isChatRoute) {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                Column {
                    // The nav bar sits on the same ground as the content, so a
                    // rule is what separates them rather than a tonal step. On a
                    // near-black scheme the default elevation tint is almost
                    // invisible, which left the bar floating with no edge.
                    HairlineDivider()
                    NavigationBar(
                        containerColor = AppSurface.raised,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { tab ->
                            val selected = current == tab.route
                            NavigationBarItem(
                                selected = selected,
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
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // No pill behind the selected icon. With five
                                    // tabs it put a permanent coloured lozenge on
                                    // screen competing with whatever the screen
                                    // itself was trying to point at; the accent on
                                    // the icon and label says the same thing.
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // A brand-new account goes through onboarding first — but only an account
        // that has never answered, and only once. `has_name` is the test rather
        // than `profile_complete`: a phone-provisioned user starts named after
        // its own number, and somebody who deliberately skipped the photo must
        // not be asked again on every cold start.
        val myProfile = com.naarni.service.ui.screens.rememberMyProfile()
        var onboarded by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(myProfile?.has_name) {
            if (!onboarded && myProfile != null && !myProfile.has_name) {
                onboarded = true
                nav.navigate("onboarding") { launchSingleTop = true }
            }
        }

        NavHost(
            navController = nav,
            startDestination = Tab.Home.route,
            // `padding` alone insets the content but does not tell anything
            // inside that the insets are already paid for, so a screen that
            // handles its own — the chat thread, which must track the keyboard —
            // applies the navigation bar a second time and leaves a dead band of
            // canvas under its composer. Consuming it makes the inner
            // `windowInsetsPadding` subtract what has already been applied and
            // add only the remainder, which is what it is designed to do.
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Tab.Home.route) {
                val profile = com.naarni.service.ui.screens.rememberMyProfile()
                var promptDismissed by rememberSaveable { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val ctx = LocalContext.current
                Column(Modifier.fillMaxSize()) {
                    // Above the screen rather than inside it: the prompt is about
                    // the account, not about anything on the home screen, and it
                    // must never push a job card out of reach.
                    if (!promptDismissed) {
                        com.naarni.service.ui.screens.ProfilePromptBanner(
                            profile = profile,
                            onOpen = { nav.navigate("editprofile") },
                            onDismiss = {
                                promptDismissed = true
                                scope.launch {
                                    runCatching { ctx.appContainer.profileRepo.snoozePrompt() }
                                }
                            },
                        )
                    }
                    HomeScreen(
                        vm = vm,
                        onCreateJobCard = { nav.navigate("create") },
                        onOpenNotifications = { nav.navigate("notifications") },
                        onOpenJobCard = { name -> nav.navigate("jobcard/$name") },
                        onOpenDuty = { nav.navigate("duty") },
                    )
                }
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
            composable(Tab.Checks.route) {
                ProcessListScreen(
                    vm,
                    onOpenProcess = { family -> nav.navigate("process/$family") },
                    onResumeRun = { run -> nav.navigate("run/$run") },
                    onOpenHistory = { nav.navigate("myinspections") },
                )
            }
            // ── Material gate ──
            //
            // One MaterialViewModel is shared by the list, the wizard, the items
            // screen and the detail screen. They are one task: giving each its
            // own would mean refetching the movement on every navigation, which
            // is a spinner between "add item" and the list of items just added.
            composable(Tab.Material.route) {
                val materialVm: MaterialViewModel = viewModel()
                MaterialListScreen(
                    vm = materialVm,
                    onOpen = { name -> nav.navigate("movement/$name") },
                    onNew = { nav.navigate("gateentry") },
                )
            }
            // One entry is one run of MATERIAL_GATE: six questions and a check,
            // captured offline like every other process. What the app used to
            // have here — a three-step wizard, then a screen of item rows, each
            // with its own sheet — asked the same information in a shape the
            // operator had to learn. The register it produced is unchanged; only
            // the way it is filled in has changed.
            composable("gateentry") {
                GateEntryScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onStarted = { runUuid ->
                        nav.navigate("run/$runUuid/$GATE_STAGE") {
                            // Drop the launcher, so Back from question one lands
                            // on the register rather than starting a second entry.
                            popUpTo("gateentry") { inclusive = true }
                        }
                    },
                )
            }
            composable("movement/{name}") { entry ->
                val materialVm: MaterialViewModel = viewModel(
                    remember(entry) { nav.getBackStackEntry(Tab.Material.route) },
                )
                MaterialDetailScreen(
                    vm = materialVm,
                    movementName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("myinspections") {
                ProcessHistoryScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenRun = { run -> nav.navigate("runreport/$run") },
                )
            }
            composable("runreport/{name}") { entry ->
                ProcessRunReportScreen(
                    vm,
                    runName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
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
                            popUpTo(Tab.Checks.route)
                        }
                    },
                )
            }
            // The board comes first, then the stepper. A pack is worked by more
            // than one person, so the first thing an operator needs is which
            // module is done, which is busy and which is free — not question one.
            composable("run/{name}") { entry ->
                ProcessBoardScreen(
                    vm,
                    runName = entry.arguments?.getString("name").orEmpty(),
                    onOpenModule = { runUuid, stage -> nav.navigate("run/$runUuid/$stage") },
                    onBack = { nav.popBackStack() },
                    onFinished = {
                        nav.navigate(Tab.Checks.route) {
                            popUpTo(Tab.Checks.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("run/{name}/{stage}") { entry ->
                ProcessRunnerScreen(
                    vm,
                    runName = entry.arguments?.getString("name").orEmpty(),
                    stageCode = entry.arguments?.getString("stage"),
                    // Submitting a module lands back on the board, where the next
                    // one is chosen — see the runner for why it is not automatic.
                    onBack = { nav.popBackStack() },
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
            composable("newgroup") {
                NewGroupScreen(
                    chatVm,
                    onBack = { nav.popBackStack() },
                    onCreated = { room ->
                        // Replace both pickers in the back stack: leaving a new
                        // group should land on the list, not back in the form
                        // that would create a second one.
                        nav.navigate("thread/$room") { popUpTo("newchat") { inclusive = true } }
                    },
                )
            }
            composable("newchat") {
                NewChatScreen(
                    chatVm,
                    onBack = { nav.popBackStack() },
                    onNewGroup = { nav.navigate("newgroup") },
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
            composable(Tab.Profile.route) {
                ProfileScreen(
                    vm,
                    onEditProfile = { nav.navigate("editprofile") },
                    onSounds = { nav.navigate("sounds") },
                )
            }
            composable("editprofile") {
                com.naarni.service.ui.screens.EditProfileScreen(onBack = { nav.popBackStack() })
            }
            composable("sounds") {
                com.naarni.service.ui.screens.NotificationSoundsScreen(onBack = { nav.popBackStack() })
            }
            composable("onboarding") {
                com.naarni.service.ui.screens.OnboardingFlow(
                    onDone = {
                        nav.navigate(Tab.Home.route) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
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
