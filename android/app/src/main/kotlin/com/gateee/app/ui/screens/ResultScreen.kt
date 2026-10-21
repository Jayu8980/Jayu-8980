package com.gateee.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.data.AttemptResult
import com.gateee.app.ui.Routes
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.EmptyState
import com.gateee.app.ui.components.HBarChart
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.app.ui.components.StatRow
import com.gateee.app.ui.components.formatSec
import com.gateee.app.ui.components.pct
import com.gateee.app.ui.theme.PaletteColors
import com.gateee.core.ErrorType

@Composable
fun ResultScreen(container: AppContainer, attemptId: Long, onBack: () -> Unit, navigate: (String) -> Unit) {
    var r by remember { mutableStateOf<AttemptResult?>(null) }
    LaunchedEffect(attemptId) { r = container.repo.result(attemptId) }
    var filter by remember { mutableStateOf("All") }
    val res = r
    ScreenScaffold("Result & Analysis", onBack) { pad ->
        if (res == null) { EmptyState("Scoring..."); return@ScreenScaffold }
        val s = res.summary
        val syl = container.repo.syllabus
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(res.test.title, style = MaterialTheme.typography.titleMedium)
                if (res.attempt.autoSubmitted) Text("Auto-submitted when time ran out", style = MaterialTheme.typography.labelSmall)
            }
            item { StatRow("Score" to "%.2f/%d".format(s.score, s.maxScore), "Accuracy" to pct(s.accuracy), "Negative" to "-%.2f".format(s.negativeMarks)) }
            item { StatRow("Est. percentile" to "%.1f".format(s.percentile), "Est. rank" to "~${s.estimatedRank}", "Time" to formatSec(s.timeTakenSec)) }
            item { StatRow("Correct" to "${s.correct}", "Wrong" to "${s.wrong}", "Skipped" to "${s.unattempted}") }
            item {
                Text(
                    "Avg time per attempted question: ${formatSec(s.avgTimePerQuestionSec)}. Percentile and rank are estimates from a model of GATE EE score spread, not official figures.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            item {
                SectionCard("Why marks were lost") {
                    val e = s.errors
                    Text("Silly mistakes: ${e[ErrorType.SILLY_MISTAKE] ?: 0}  (topic was strong, or the answer was close)")
                    Text("Concept gaps: ${e[ErrorType.CONCEPT_GAP] ?: 0}")
                    Text("Time-pressure errors: ${e[ErrorType.TIME_PRESSURE] ?: 0}  (rushed, or answered in the last 10% of time)")
                }
            }
            item {
                SectionCard("Subject-wise accuracy") {
                    HBarChart(s.bySubject.filter { it.attempted > 0 }.map { syl.subjectName(it.key) to it.accuracy * 100 }) { "%.0f%%".format(it) }
                }
            }
            item {
                SectionCard("Strengths and weaknesses by topic") {
                    val attempted = s.byTopic.filter { it.attempted > 0 }
                    attempted.take(4).forEach { Text("Weak: ${syl.topicName(it.key)} - ${it.correct}/${it.attempted}", color = PaletteColors.wrong) }
                    attempted.takeLast(4).reversed().filter { it.accuracy >= 0.75 }.forEach { Text("Strong: ${syl.topicName(it.key)} - ${it.correct}/${it.attempted}", color = PaletteColors.correct) }
                }
            }
            item {
                SectionCard("Revise next (vs PYQ weightage)") {
                    res.suggestions.forEach { (t, why) -> Text("- ${t.name}: $why", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { ChipRow(listOf("All", "Wrong", "Skipped", "Correct"), filter, { it }) { filter = it } }
            items(
                res.questions.filter { q ->
                    val rr = res.responses[q.id]
                    when (filter) {
                        "Wrong" -> rr?.isCorrect == false
                        "Correct" -> rr?.isCorrect == true
                        "Skipped" -> rr?.isCorrect == null
                        else -> true
                    }
                },
                key = { it.id },
            ) { q ->
                val rr = res.responses[q.id]
                val (label, color) = when (rr?.isCorrect) {
                    true -> "Correct +%.2f".format(rr?.marksAwarded ?: 0.0) to PaletteColors.correct
                    false -> "Wrong %.2f".format(rr?.marksAwarded ?: 0.0) + (rr?.errorType?.let { " - " + it.lowercase().replace('_', ' ') } ?: "") to PaletteColors.wrong
                    null -> "Not attempted" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(Modifier.fillMaxWidth().clickable { navigate(Routes.question(q.id)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Q${q.qno} - ${q.type} - ${q.marks}M", style = MaterialTheme.typography.labelMedium)
                            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(q.question, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text("Your answer: ${rr?.answer ?: "-"}   Key: ${q.answer}   Time: ${formatSec(rr?.timeSpentSec ?: 0)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Column(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}
