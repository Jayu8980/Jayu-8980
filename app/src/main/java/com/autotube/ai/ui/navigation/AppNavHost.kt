package com.autotube.ai.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autotube.ai.ui.error.ErrorScreen
import com.autotube.ai.ui.home.HomeScreen
import com.autotube.ai.ui.progress.ProgressScreen
import com.autotube.ai.ui.projects.ProjectsScreen
import com.autotube.ai.ui.review.ReviewScreen
import com.autotube.ai.ui.settings.SettingsScreen
import com.autotube.ai.ui.studio.StudioEditorScreen
import com.autotube.ai.ui.studio.StudioHomeScreen
import com.autotube.ai.ui.studio.StudioImportScreen

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    CREATE(Routes.HOME, "Create", Icons.Rounded.AutoAwesome),
    STUDIO(Routes.STUDIO, "Studio", Icons.Rounded.ContentCut),
    PROJECTS(Routes.PROJECTS, "Projects", Icons.Rounded.VideoLibrary),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
}

/** Switches bottom-bar tabs keeping each tab's state. */
fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * App scaffold: bottom bar (Create / Projects / Settings) and the NavHost with the project detail
 * routes. [deepLink] (from a notification) is navigated to once and then reported via [onDeepLinkHandled].
 */
@Composable
fun AppNavHost(
    deepLink: DeepLink?,
    onDeepLinkHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null || TopLevel.entries.any { top -> destination.hierarchy.any { it.route == top.route } }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            navController.navigate(deepLink.route) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TopLevel.entries.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenProgress = { id -> navController.navigate(Routes.progress(id)) },
                    onOpenProject = { project -> navController.navigate(Routes.forProject(project)) },
                    onSeeAllProjects = { navController.navigateTopLevel(Routes.PROJECTS) },
                    onOpenSettings = { navController.navigateTopLevel(Routes.SETTINGS) },
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    onOpenProject = { project -> navController.navigate(Routes.forProject(project)) },
                    onCreate = { navController.navigateTopLevel(Routes.HOME) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.STUDIO) {
                StudioHomeScreen(onOpenEditor = { id -> navController.navigate(Routes.studioEditor(id)) })
            }
            composable(
                route = Routes.STUDIO_EDITOR,
                arguments = listOf(navArgument(Routes.ARG_STUDIO_ID) { type = NavType.StringType }),
            ) {
                StudioEditorScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(Routes.STUDIO) },
                )
            }
            composable(
                route = Routes.STUDIO_IMPORT,
                arguments = listOf(
                    navArgument(Routes.ARG_IMPORT_PATH) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Routes.ARG_IMPORT_NAME) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                StudioImportScreen(
                    path = entry.arguments?.getString(Routes.ARG_IMPORT_PATH).orEmpty(),
                    name = entry.arguments?.getString(Routes.ARG_IMPORT_NAME).orEmpty(),
                    onOpenEditor = { id ->
                        navController.navigate(Routes.studioEditor(id)) {
                            popUpTo(Routes.STUDIO_IMPORT) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.PROGRESS,
                arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
            ) {
                ProgressScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(Routes.HOME) },
                    onOpenReview = { id ->
                        navController.navigate(Routes.review(id)) {
                            popUpTo(Routes.PROGRESS) { inclusive = true }
                        }
                    },
                    onOpenError = { id ->
                        navController.navigate(Routes.error(id)) {
                            popUpTo(Routes.PROGRESS) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.REVIEW,
                arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
            ) {
                ReviewScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(Routes.HOME) },
                    onOpenProgress = { id ->
                        navController.navigate(Routes.progress(id)) {
                            popUpTo(Routes.REVIEW) { inclusive = true }
                        }
                    },
                    onOpenError = { id -> navController.navigate(Routes.error(id)) },
                    onEditInStudio = { path, title -> navController.navigate(Routes.studioImport(path, title)) },
                )
            }
            composable(
                route = Routes.ERROR,
                arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
            ) {
                ErrorScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(Routes.HOME) },
                    onOpenProgress = { id ->
                        navController.navigate(Routes.progress(id)) {
                            popUpTo(Routes.ERROR) { inclusive = true }
                        }
                    },
                    onOpenReview = { id ->
                        navController.navigate(Routes.review(id)) {
                            popUpTo(Routes.ERROR) { inclusive = true }
                        }
                    },
                    onOpenSettings = { navController.navigateTopLevel(Routes.SETTINGS) },
                )
            }
        }
    }
}
