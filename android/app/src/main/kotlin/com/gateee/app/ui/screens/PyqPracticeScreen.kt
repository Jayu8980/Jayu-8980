package com.gateee.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.core.Question

@Composable
fun PyqPracticeScreen(container: AppContainer, onBack: () -> Unit, openQuestion: (String) -> Unit) {
    val repo = container.repo
    var pyqs by remember { mutableStateOf<List<Question>>(emptyList()) }
    var fallback by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repo.seedIfNeeded()
        val real = repo.questionsBySource("pyq")
        fallback = real.isEmpty()
        pyqs = real.ifEmpty { repo.questionsBySource("sample") }
    }
    var year by remember { mutableStateOf<Int?>(null) }
    var subject by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf<String?>(null) }

    val years = pyqs.mapNotNull { it.year }.distinct().sortedDescending()
    val subjects = pyqs.map { it.subjectId }.distinct()
    val topics = pyqs.filter { subject == null || it.subjectId == subject }.map { it.topicId }.distinct()
    val shown = pyqs.filter { (year == null || it.year == year) && (subject == null || it.subjectId == subject) && (topic == null || it.topicId == topic) }

    ScreenScaffold("PYQ Practice", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fallback) item {
                InfoBanner("No PYQ text bundled yet: add real questions with solutions to data/pyq_bank.json. Showing the sample bank meanwhile.")
            }
            if (years.isNotEmpty()) item { ChipRow(listOf<Int?>(null) + years, year, { it?.toString() ?: "All years" }) { year = it } }
            item { ChipRow(listOf<String?>(null) + subjects, subject, { it?.let(repo.syllabus::subjectName) ?: "All subjects" }) { subject = it; topic = null } }
            if (subject != null) item { ChipRow(listOf<String?>(null) + topics, topic, { it?.let(repo.syllabus::topicName) ?: "All topics" }) { topic = it } }
            item { Text("${shown.size} questions", style = MaterialTheme.typography.labelMedium) }
            items(shown, key = { it.id }) { q ->
                Card(Modifier.fillMaxWidth().clickable { openQuestion(q.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${q.year?.let { "GATE $it - " } ?: ""}${q.subject.ifBlank { q.subjectId }} - ${q.type} - ${q.marks}M", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(q.question, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
