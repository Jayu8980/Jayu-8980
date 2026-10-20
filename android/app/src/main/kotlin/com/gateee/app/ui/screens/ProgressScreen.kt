package com.gateee.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.ui.Routes
import com.gateee.app.ui.components.EmptyState
import com.gateee.app.ui.components.LineChart
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.app.ui.theme.PaletteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProgressScreen(container: AppContainer, onBack: () -> Unit, navigate: (String) -> Unit) {
    val attempts by container.repo.submittedAttempts().collectAsState(initial = emptyList())
    val strengths by container.repo.strengths().collectAsState(initial = emptyList())
    val syl = container.repo.syllabus
    val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
    ScreenScaffold("Progress", onBack) { pad ->
        if (attempts.isEmpty()) { EmptyState("Finish a test to see your progress graph."); return@ScreenScaffold }
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("Score % across all mocks") {
                    LineChart(
                        attempts.map { fmt.format(Date(it.submittedAt ?: 0)) },
                        listOf(
                            "Score %" to attempts.map { if (it.maxScore > 0) (it.score / it.maxScore * 100).coerceAtLeast(0.0) else 0.0 },
                            "Accuracy %" to attempts.map { val a = it.correct + it.wrong; if (a > 0) it.correct * 100.0 / a else 0.0 },
                        ),
                    )
                }
            }
            item {
                SectionCard("Topic-strength map (weakest first)") {
                    if (strengths.isEmpty()) Text("No data yet.")
                    strengths.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(syl.topicName(s.topicId), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Box(Modifier.width(120.dp).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(s.strength.toFloat().coerceIn(0.02f, 1f)).height(12.dp).background(lerp(PaletteColors.wrong, PaletteColors.correct, s.strength.toFloat().coerceIn(0f, 1f)), RoundedCornerShape(3.dp)))
                            }
                            Text(" ${(s.strength * 100).toInt()}% (${s.attempts})", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item { Text("Attempts", style = MaterialTheme.typography.titleMedium) }
            items(attempts.reversed(), key = { it.id }) { a ->
                SectionCard {
                    Row(Modifier.fillMaxWidth().clickable { navigate(Routes.result(a.id)) }, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${fmt.format(Date(a.submittedAt ?: 0))} - ${a.testId.take(28)}", style = MaterialTheme.typography.bodySmall)
                        Text("%.1f/%d".format(a.score, a.maxScore), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
