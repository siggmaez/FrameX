package app.framex

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.framex.feature.home.HomeScreen
import app.framex.feature.games.GamesScreen
import app.framex.feature.monitor.MonitorScreen
import app.framex.feature.optimize.OptimizeScreen
import app.framex.feature.settings.SettingsScreen

private enum class Destination(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home("home", R.string.nav_home, Icons.Outlined.Home),
    Optimize("optimize", R.string.nav_optimize, Icons.Outlined.Tune),
    Games("games", R.string.nav_games, Icons.Outlined.SportsEsports),
    Monitor("monitor", R.string.nav_monitor, Icons.Outlined.Insights),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun FrameXApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == d.route } == true,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Destination.Home.route) {
            composable(Destination.Home.route) { HomeScreen(padding) }
            composable(Destination.Optimize.route) { OptimizeScreen(padding) }
            composable(Destination.Games.route) { GamesScreen(padding) }
            composable(Destination.Monitor.route) { MonitorScreen(padding) }
            composable(Destination.Settings.route) { SettingsScreen(padding) }
        }
    }
}

/** Honest empty state for sections that have no functionality yet. */
@Composable
private fun NotBuiltYet(title: String, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.not_built), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
