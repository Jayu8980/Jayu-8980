package com.gateee.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gateee.app.AppContainer
import com.gateee.app.ui.screens.AiMockScreen
import com.gateee.app.ui.screens.AnalysisScreen
import com.gateee.app.ui.screens.FormulaScreen
import com.gateee.app.ui.screens.HomeScreen
import com.gateee.app.ui.screens.PaperReportScreen
import com.gateee.app.ui.screens.ProgressScreen
import com.gateee.app.ui.screens.PyqPracticeScreen
import com.gateee.app.ui.screens.QuestionDetailScreen
import com.gateee.app.ui.screens.ResultScreen
import com.gateee.app.ui.screens.RevisionScreen
import com.gateee.app.ui.screens.SeriesScreen
import com.gateee.app.ui.screens.SettingsScreen
import com.gateee.app.ui.screens.TestScreen

object Routes {
    const val HOME = "home"
    const val ANALYSIS = "analysis"
    const val PYQ = "pyq"
    const val SERIES = "series"
    const val AI = "ai?mode={mode}"
    const val REPORT = "report/{testId}"
    const val TEST = "test/{testId}"
    const val RESULT = "result/{attemptId}"
    const val PROGRESS = "progress"
    const val REVISION = "revision"
    const val QUESTION = "question/{questionId}"
    const val FORMULAS = "formulas"
    const val SETTINGS = "settings"

    fun ai(mode: String = "full_mock") = "ai?mode=$mode"
    fun report(testId: String) = "report/$testId"
    fun test(testId: String) = "test/$testId"
    fun result(attemptId: Long) = "result/$attemptId"
    fun question(id: String) = "question/$id"
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(container) { nav.navigate(it) } }
        composable(Routes.ANALYSIS) { AnalysisScreen(container, back) }
        composable(Routes.PYQ) { PyqPracticeScreen(container, back) { nav.navigate(Routes.question(it)) } }
        composable(Routes.SERIES) { SeriesScreen(container, back) { nav.navigate(it) } }
        composable(Routes.AI, listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "full_mock" })) {
            AiMockScreen(container, it.arguments?.getString("mode") ?: "full_mock", back) { testId ->
                nav.navigate(Routes.report(testId)) { popUpTo(Routes.HOME) }
            }
        }
        composable(Routes.REPORT, listOf(navArgument("testId") { type = NavType.StringType })) {
            PaperReportScreen(container, it.arguments!!.getString("testId")!!, back) { id -> nav.navigate(Routes.test(id)) }
        }
        composable(Routes.TEST, listOf(navArgument("testId") { type = NavType.StringType })) {
            TestScreen(container, it.arguments!!.getString("testId")!!, back) { attemptId ->
                nav.navigate(Routes.result(attemptId)) { popUpTo(Routes.HOME) }
            }
        }
        composable(Routes.RESULT, listOf(navArgument("attemptId") { type = NavType.LongType })) {
            ResultScreen(container, it.arguments!!.getLong("attemptId"), back) { route -> nav.navigate(route) }
        }
        composable(Routes.PROGRESS) { ProgressScreen(container, back) { route -> nav.navigate(route) } }
        composable(Routes.REVISION) { RevisionScreen(container, back) { nav.navigate(Routes.question(it)) } }
        composable(Routes.QUESTION, listOf(navArgument("questionId") { type = NavType.StringType })) {
            QuestionDetailScreen(container, it.arguments!!.getString("questionId")!!, back)
        }
        composable(Routes.FORMULAS) { FormulaScreen(container, back) }
        composable(Routes.SETTINGS) { SettingsScreen(container, back) }
    }
}
