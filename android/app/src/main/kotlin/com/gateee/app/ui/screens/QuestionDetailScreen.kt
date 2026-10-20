package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.ui.components.EmptyState
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.core.QType
import com.gateee.core.Question
import kotlinx.coroutines.launch

/** Question + full solution, with bookmark, personal note and "Report wrong question". */
@Composable
fun QuestionDetailScreen(container: AppContainer, questionId: String, onBack: () -> Unit) {
    val repo = container.repo
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf<Question?>(null) }
    LaunchedEffect(questionId) { q = repo.question(questionId) }
    val bookmarked by repo.isBookmarked(questionId).collectAsState(initial = false)
    val note by repo.note(questionId).collectAsState(initial = null)
    var noteText by remember(note) { mutableStateOf(note?.text.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var reportMsg by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Question", onBack, actions = {
        IconButton(onClick = { scope.launch { repo.setBookmark(questionId, !bookmarked) } }) {
            Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Bookmark")
        }
        IconButton(onClick = { reporting = true }) { Icon(Icons.Default.Flag, "Report wrong question") }
    }) { pad ->
        val qq = q
        if (qq == null) { EmptyState("Loading..."); return@ScreenScaffold }
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${qq.year?.let { "GATE $it - " } ?: ""}${qq.subject.ifBlank { qq.subjectId }} - ${qq.topic} - ${qq.subtopic}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${qq.type} - ${qq.marks} mark(s) - ${qq.difficulty} - ${qq.cognitiveLevel}", style = MaterialTheme.typography.labelSmall)
            Text(qq.question, style = MaterialTheme.typography.bodyLarge)
            qq.options.forEachIndexed { i, o -> Text("(${"ABCD"[i]}) $o") }
            reportMsg?.let { InfoBanner(it) }
            if (!reveal) Button(onClick = { reveal = true }, modifier = Modifier.fillMaxWidth()) { Text("Show answer and solution") }
            else {
                SectionCard("Answer") {
                    Text(
                        qq.answerRange?.takeIf { qq.type == QType.NAT }?.let { r -> "${qq.answer}  (accepted ${r.min} to ${r.max})" } ?: qq.answer,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    qq.verificationReport?.let { v ->
                        Text("Verified: ${v.method.replace('_', ' ')} (${v.support})" + if (v.codeCheck == "pass") ", NAT value recomputed in code" else "", style = MaterialTheme.typography.labelSmall)
                    }
                }
                SectionCard("Step-by-step solution") { Text(qq.solution) }
                if (qq.concept.isNotBlank()) SectionCard("Concept used") { Text(qq.concept) }
                if (qq.commonTrap.isNotBlank()) SectionCard("Common trap") { Text(qq.commonTrap) }
                if (qq.distractorLogic.isNotBlank()) SectionCard("Why the wrong options tempt") { Text(qq.distractorLogic) }
                if (qq.relatedPyqYear.isNotBlank()) SectionCard("Related PYQ") { Text(qq.relatedPyqYear) }
            }
            SectionCard("My notes") {
                OutlinedTextField(noteText, { noteText = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Write a note for revision") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { repo.saveNote(questionId, noteText) } }) { Text("Save note") }
                }
            }
        }
    }

    if (reporting) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("Report wrong question") },
            text = {
                Column {
                    Text("The question is removed from your question bank and reported so it is not generated again.")
                    OutlinedTextField(reason, { reason = it }, label = { Text("What is wrong?") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reporting = false
                    scope.launch {
                        val sent = repo.reportQuestion(questionId, reason)
                        reportMsg = if (sent) "Reported and removed from the bank." else "Removed from your bank (server not reachable; it will not appear in new tests)."
                    }
                }) { Text("Report") }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("Cancel") } },
        )
    }
}
