package com.fitnessapp.summary.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.ui.day.DayScreen
import com.fitnessapp.summary.ui.settings.SettingsScreen
import com.fitnessapp.summary.ui.trends.TrendsScreen
import com.fitnessapp.summary.ui.week.WeekScreen
import com.fitnessapp.summary.ui.workouts.WorkoutsScreen

object Routes {
    const val DAY = "day"
    const val WEEK = "week"
    const val TRENDS = "trends"
    const val WORKOUTS = "workouts"
    const val SETTINGS = "settings"
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val BOTTOM_TABS = listOf(
    BottomTab(Routes.DAY, "День", Icons.Filled.Today),
    BottomTab(Routes.WEEK, "Неделя", Icons.Filled.CalendarViewWeek),
    BottomTab(Routes.TRENDS, "Тренды", Icons.Filled.Insights),
    BottomTab(Routes.WORKOUTS, "Тренировки", Icons.Filled.FitnessCenter),
    BottomTab(Routes.SETTINGS, "Я", Icons.Filled.Person)
)

/**
 * Five flat tabs, no nested graphs.
 *
 * Every route is a bare string with no query parameters on purpose: a route carrying
 * an argument stops matching its tab's template, which silently breaks the bottom
 * bar's selected-tab highlight. Anything that needs to pass data between screens
 * should go through `savedStateHandle`, not the URL.
 */
@Composable
fun AppNavigation(app: FitnessSummaryApp) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                BOTTOM_TABS.forEach { tab ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DAY,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(Routes.DAY) { DayScreen(app = app) }
            composable(Routes.WEEK) { WeekScreen(app = app) }
            composable(Routes.TRENDS) { TrendsScreen(app = app) }
            composable(Routes.WORKOUTS) { WorkoutsScreen(app = app) }
            composable(Routes.SETTINGS) { SettingsScreen(app = app) }
        }
    }
}
