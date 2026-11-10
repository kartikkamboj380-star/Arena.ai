package com.kartikkamboj.arena.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.ui.components.NoticeBanner
import com.kartikkamboj.arena.ui.components.NoticeTone
import com.kartikkamboj.arena.ui.screens.AccountsScreen
import com.kartikkamboj.arena.ui.screens.ChatScreen
import com.kartikkamboj.arena.ui.screens.HistoryScreen
import com.kartikkamboj.arena.ui.screens.ModelsScreen
import com.kartikkamboj.arena.ui.screens.ProjectsScreen
import com.kartikkamboj.arena.ui.screens.SettingsScreen
import com.kartikkamboj.arena.web.UiAction
import com.kartikkamboj.arena.ui.theme.ArenaTheme

/**
 * The sections of the app, in bottom-bar order.
 *
 * [inBar] is false for Projects on purpose: five tabs is the most a 5-inch screen labels legibly, and
 * a project is not a place you navigate to, it is the thing you are already looking at — so it opens
 * from the Chat header strip and from History, and keeps its own route for state restoration.
 */
enum class ArenaDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBar: Boolean = true,
) {
    CHAT("chat", "Chat", Icons.Filled.Forum),
    PROJECTS("projects", "Projects", Icons.Filled.Folder, inBar = false),
    MODELS("models", "Models", Icons.Filled.Memory),
    HISTORY("history", "History", Icons.Filled.History),
    ACCOUNTS("accounts", "Accounts", Icons.Filled.Person),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Navigation shell: bottom bar for the five sections, one NavHost, and a banner strip for the
 * notices that must survive a tab change (session expiry, rate limits, sign-in hand-offs).
 *
 * Nothing here tears down the web session: switching to Models or Accounts leaves the Chat tab's
 * WebView attached to the coordinator, so a running answer keeps streaming and coming back shows it
 * where the user left it.
 */
@Composable
fun ArenaApp(
    container: AppContainer,
    pendingLink: State<String?>,
    onLinkConsumed: () -> Unit,
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    ArenaTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val notice by container.session.notice.collectAsStateWithLifecycle()
        val session by container.session.state.collectAsStateWithLifecycle()
        val selectedSlot by container.accounts.selection.collectAsStateWithLifecycle()

        // A deep link is only honoured once a slot owns the session; until then it stays pending.
        LaunchedEffect(pendingLink.value, selectedSlot) {
            val url = pendingLink.value ?: return@LaunchedEffect
            val slot = selectedSlot ?: return@LaunchedEffect
            navController.navigate(ArenaDestination.CHAT.route) {
                launchSingleTop = true
                restoreState = true
            }
            container.session.openFor(slot, url, force = true)
            onLinkConsumed()
        }

        val localContext = LocalContext.current

        // Back inside Chat walks the page's own history first, and only leaves the app when the
        // session has nowhere left to go.
        BackHandler(enabled = currentRoute == ArenaDestination.CHAT.route && session.canGoBack) {
            container.session.goBack()
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            bottomBar = {
                Column {
                    notice?.let { current ->
                        NoticeBanner(
                            text = current.text,
                            tone = when (current.kind) {
                                com.kartikkamboj.arena.web.UiNotice.Kind.INFO -> NoticeTone.INFO
                                com.kartikkamboj.arena.web.UiNotice.Kind.SUCCESS -> NoticeTone.SUCCESS
                                com.kartikkamboj.arena.web.UiNotice.Kind.WARN -> NoticeTone.WARN
                                com.kartikkamboj.arena.web.UiNotice.Kind.ERROR -> NoticeTone.ERROR
                            },
                            actionLabel = current.actionLabel,
                            onAction = { handleNoticeAction(current.action, navController, container, localContext) },
                            onDismiss = { container.session.consumeNotice() },
                        )
                    }
                    ArenaBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { destination ->
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { insets ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = ArenaDestination.CHAT.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(ArenaDestination.CHAT.route) {
                        ChatScreen(container = container, navigator = navController)
                    }
                    composable(ArenaDestination.PROJECTS.route) {
                        ProjectsScreen(container = container, navigator = navController)
                    }
                    composable(ArenaDestination.MODELS.route) {
                        ModelsScreen(container = container, navigator = navController)
                    }
                    composable(ArenaDestination.HISTORY.route) {
                        HistoryScreen(container = container, navigator = navController)
                    }
                    composable(ArenaDestination.ACCOUNTS.route) {
                        AccountsScreen(container = container, navigator = navController)
                    }
                    composable(ArenaDestination.SETTINGS.route) {
                        SettingsScreen(container = container, navigator = navController)
                    }
                }
            }
        }
    }
}

/**
 * Runs the action a notice banner offered. Deliberately *not* composable: it is called from a click
 * lambda, where composable calls are illegal, so the caller hands over the context it already has.
 */
private fun handleNoticeAction(
    action: UiAction?,
    navigator: NavHostController,
    container: AppContainer,
    context: android.content.Context,
) {
    when (action) {
        UiAction.RECONNECT -> {
            // Not a plain reload: this starts the sign-in window in which the identity provider is
            // allowed to finish inside the app, which is what makes the session land in this slot's
            // jar instead of in a browser this client cannot read.
            container.session.beginSignIn()
            navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true }
        }

        UiAction.OPEN_ACCOUNTS -> navigator.navigate(ArenaDestination.ACCOUNTS.route) { launchSingleTop = true }
        UiAction.OPEN_LEADERBOARD -> {
            container.session.loadUrlInSession(com.kartikkamboj.arena.web.ArenaUrls.LEADERBOARD)
            navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true }
        }

        UiAction.RETRY -> container.session.reload()
        UiAction.BROWSER -> {
            val url = container.session.state.value.url
            val activity = context as? ComponentActivity ?: context.findActivity()
            if (url.isNotBlank() && activity != null) {
                runCatching {
                    activity.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                }
            }
        }

        null -> Unit
    }
    container.session.consumeNotice()
}

/** Unwraps a compose/ContextThemeWrapper context chain to the activity that owns it. */
private fun android.content.Context.findActivity(): ComponentActivity? {
    var current: android.content.Context = this
    while (current is android.content.ContextWrapper) {
        (current as? ComponentActivity)?.let { return it }
        current = current.baseContext
    }
    return null
}

@Composable
private fun ArenaBottomBar(currentRoute: String?, onSelect: (ArenaDestination) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        ArenaDestination.entries.filter { it.inBar }.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.padding(2.dp),
                    )
                },
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
