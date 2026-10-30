package com.pianoscribe.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.ui.convert.ConvertScreen
import com.pianoscribe.app.ui.home.HomeScreen
import com.pianoscribe.app.ui.prepare.PrepareScreen
import com.pianoscribe.app.ui.record.RecordScreen
import com.pianoscribe.app.ui.result.ResultScreen
import com.pianoscribe.app.ui.settings.LicensesScreen
import com.pianoscribe.app.ui.settings.SettingsScreen
import com.pianoscribe.app.ui.studio.StudioScreen

object Routes {
    const val HOME = "home"
    const val RECORD = "record"
    const val PREPARE = "prepare?uri={uri}"
    const val CONVERT = "convert/{start}/{end}"
    const val RESULT = "result/{id}"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    const val STUDIO = "studio"

    fun prepare(uri: String?) = if (uri == null) "prepare" else "prepare?uri=" + android.net.Uri.encode(uri)
    fun convert(start: Int, end: Int) = "convert/$start/$end"
    fun result(id: Long) = "result/$id"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                container,
                onImport = { uri -> nav.navigate(Routes.prepare(uri.toString())) },
                onRecord = { nav.navigate(Routes.RECORD) },
                onOpen = { id -> nav.navigate(Routes.result(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(container, onBack = { nav.popBackStack() }, onRecorded = {
                nav.navigate(Routes.prepare(null)) { popUpTo(Routes.HOME) }
            })
        }
        composable(Routes.PREPARE, arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            PrepareScreen(
                container,
                uri = entry.arguments?.getString("uri"),
                onBack = { nav.popBackStack() },
                onConvert = { s, e -> nav.navigate(Routes.convert(s, e)) },
                onStudio = { nav.navigate(Routes.STUDIO) },
                onOpenProject = { id -> nav.navigate(Routes.result(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.STUDIO) { StudioScreen(container, onDone = { nav.popBackStack() }) }
        composable(Routes.CONVERT, arguments = listOf(navArgument("start") { type = NavType.IntType }, navArgument("end") { type = NavType.IntType })) { entry ->
            ConvertScreen(
                container,
                start = entry.arguments!!.getInt("start"),
                end = entry.arguments!!.getInt("end"),
                onCancel = { nav.popBackStack() },
                onDone = { id -> nav.navigate(Routes.result(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.RESULT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ResultScreen(container, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }, onLicenses = { nav.navigate(Routes.LICENSES) }) }
        composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
    }
}
