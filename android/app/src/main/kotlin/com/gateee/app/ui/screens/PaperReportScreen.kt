package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.data.LoadedTest
import com.gateee.app.ui.components.DifficultyStrip
import com.gateee.app.ui.components.EmptyState
import com.gateee.app.ui.components.HBarChart
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.app.ui.components.StatRow
import com.gateee.core.BlueprintSlot
import com.gateee.core.GateJson
import com.gateee.core.PaperReport
import com.gateee.core.VerificationSummary

/** "Paper Report": blueprint, difficulty curve, subject coverage, expected topper score and cutoff. */
@Composable
fun PaperReportScreen(container: AppContainer, testId: String, onBack: () -> Unit, start: (String) -> Unit) {
    var loaded by remember { mutableStateOf<LoadedTest?>(null) }
    LaunchedEffect(testId) { loaded = container.repo.loadTest(testId) }
    val l = loaded
    ScreenScaffold("Paper Report", onBack) { pad ->
        if (l == null) { EmptyState("Loading..."); return@ScreenScaffold }
        val qs = l.questions
        val report = l.test.paperReportJson?.let { GateJson.decodeFromString<PaperReport>(it) }
        val verification = l.test.verificationJson?.let { GateJson.decodeFromString<VerificationSummary>(it) }
        val blueprint = l.test.blueprintJson?.let { GateJson.decodeFromString<List<BlueprintSlot>>(it) }.orEmpty()
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(l.test.title, style = MaterialTheme.typography.titleLarge)
                Text("${qs.size} questions - ${qs.sumOf { it.marks }} marks - ${l.test.durationMin} min", style = MaterialTheme.typography.bodyMedium)
            }
            item { Button(onClick = { start(testId) }, modifier = Modifier.fillMaxWidth(), enabled = qs.isNotEmpty()) { Text("Start test") } }
            if (report != null) item {
                StatRow(
                    "Expected topper" to "%.0f".format(report.expectedTopperScore),
                    "Expected cutoff" to "%.0f".format(report.expectedCutoff),
                    "Expected avg" to "%.0f".format(report.expectedAverageScore),
                )
            }
            if (verification != null) item {
                SectionCard("Multi-agent answer verification") {
                    Text("Unanimous (3/3 solvers + setter): ${verification.unanimous}")
                    Text("Resolved by adjudicator: ${verification.adjudicated}")
                    Text("Answer corrected by adjudicator: ${verification.correctedByAdjudicator}")
                    Text("NAT answers recomputed in code: ${verification.codeChecked}")
                    Text("Drafts rejected and regenerated: ${verification.rejectedDrafts}")
                }
            }
            report?.patternIssues?.takeIf { it.isNotEmpty() }?.let { item { InfoBanner("Pattern notes: " + it.joinToString("; ")) } }
            item {
                SectionCard("Difficulty curve") { DifficultyStrip(qs.map { it.difficulty }) }
            }
            item {
                SectionCard("Subject coverage (marks)") {
                    HBarChart(qs.groupBy { it.subjectId }.map { (s, l2) -> container.repo.syllabus.subjectName(s) to l2.sumOf { it.marks }.toDouble() }.sortedByDescending { it.second }) { "%.0f".format(it) }
                }
            }
            item {
                SectionCard("Type mix") {
                    Text(qs.groupingBy { it.type }.eachCount().entries.joinToString("   ") { "${it.key}: ${it.value}" })
                    val figs = qs.mapNotNull { it.figure?.type }
                    if (figs.isNotEmpty()) Text("Questions with figures: ${figs.size} (" + figs.groupingBy { it.replace('_', ' ') }.eachCount().entries.joinToString { "${it.key} ${it.value}" } + ")", style = MaterialTheme.typography.bodySmall)
                    Text(qs.groupingBy { it.cognitiveLevel.ifBlank { "-" } }.eachCount().entries.joinToString("   ") { "${it.key}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (report?.balanceNotes?.isNotBlank() == true) item { SectionCard("Moderator notes") { Text(report.balanceNotes, style = MaterialTheme.typography.bodySmall) } }
            if (blueprint.isNotEmpty()) item {
                SectionCard("Blueprint") {
                    Row { listOf("Q" to 32, "Subject / subtopic" to 0, "Type" to 44, "M" to 24, "Diff" to 52).forEach { (h, w) -> Text(h, if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp), style = MaterialTheme.typography.labelSmall) } }
                    blueprint.forEach { b ->
                        Row {
                            Text("${b.qno}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall)
                            Text("${b.subjectId} - ${b.subtopic}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(b.type.name, Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                            Text("${b.marks}", Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall)
                            Text(b.difficulty, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Column(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}
