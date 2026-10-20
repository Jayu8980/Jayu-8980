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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.core.Question

@Composable
fun RevisionScreen(container: AppContainer, onBack: () -> Unit, open: (String) -> Unit) {
    val repo = container.repo
    var tab by remember { mutableIntStateOf(0) }
    val bookmarks by repo.bookmarks().collectAsState(initial = emptyList())
    val notes by repo.notes().collectAsState(initial = emptyList())
    val wrong by repo.wrongAnswers().collectAsState(initial = emptyList())
    val ids = when (tab) {
        0 -> bookmarks.map { it.questionId }
        1 -> wrong.map { it.questionId }.distinct()
        else -> notes.map { it.questionId }
    }
    var questions by remember { mutableStateOf<Map<String, Question>>(emptyMap()) }
    LaunchedEffect(ids) { questions = ids.mapNotNull { repo.question(it) }.associateBy { it.id } }

    ScreenScaffold("Revision", onBack) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Bookmarks (${bookmarks.size})", "Wrong answers", "Notes (${notes.size})").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Column(Modifier.padding(top = 4.dp)) {} }
                if (ids.isEmpty()) item { Text("Nothing here yet.", Modifier.padding(16.dp)) }
                items(ids, key = { it }) { id ->
                    val q = questions[id] ?: return@items
                    Card(Modifier.fillMaxWidth().clickable { open(id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${q.subject.ifBlank { q.subjectId }} - ${q.topic}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(q.question, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (tab == 2) notes.firstOrNull { it.questionId == id }?.let { Text("Note: ${it.text}", style = MaterialTheme.typography.labelSmall) }
                            if (tab == 1) wrong.firstOrNull { it.questionId == id }?.let { Text("Your answer ${it.answer ?: "-"} - ${it.errorType?.lowercase()?.replace('_', ' ') ?: ""}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}
