package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.data.JobStatus
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.core.GenerateRequest
import com.gateee.core.OfflineAssembler
import com.gateee.core.Paper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MODES = listOf(
    "full_mock" to "Instant Full Mock",
    "subject" to "Subject Mock",
    "topic" to "Topic Mock",
    "weak_area" to "Weak-Area Mock",
    "daily" to "Daily Live Practice",
    "custom" to "Custom",
)

/** Starts a backend job and polls it until the multi-agent pipeline finishes. */
suspend fun runGeneration(container: AppContainer, req: GenerateRequest, onStatus: (JobStatus) -> Unit): Paper {
    val withAvoid = req.copy(avoidSummaries = container.repo.avoidSummaries())
    val jobId = container.backend.startGeneration(withAvoid)
    while (true) {
        delay(2500)
        val s = container.backend.job(jobId)
        onStatus(s)
        when (s.status) {
            "done" -> return container.backend.paper(jobId)
            "failed" -> throw IllegalStateException(s.error ?: "generation failed")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiMockScreen(container: AppContainer, initialMode: String, onBack: () -> Unit, openReport: (String) -> Unit) {
    val repo = container.repo
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    val subjects = remember { mutableStateOf(setOf<String>()) }
    val topics = remember { mutableStateOf(setOf<String>()) }
    var count by remember { mutableFloatStateOf(20f) }
    var difficulty by remember { mutableStateOf("Mixed") }
    var natShare by remember { mutableFloatStateOf(0.28f) }
    var msqShare by remember { mutableFloatStateOf(0.14f) }
    var weak by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<JobStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { weak = repo.weakTopics() }

    fun request(): GenerateRequest = when (mode) {
        "full_mock" -> GenerateRequest(mode = "full_mock")
        "subject" -> GenerateRequest(mode = "subject", subjects = subjects.value.toList(), questionCount = count.toInt())
        "topic" -> GenerateRequest(mode = "topic", topics = topics.value.toList(), questionCount = count.toInt())
        "weak_area" -> GenerateRequest(mode = "weak_area", weakTopics = weak, questionCount = count.toInt())
        "daily" -> GenerateRequest(mode = "daily", questionCount = 12, weakTopics = weak.ifEmpty { null })
        else -> GenerateRequest(
            mode = "custom",
            subjects = subjects.value.toList().ifEmpty { null },
            questionCount = count.toInt(),
            difficulty = difficulty,
            typeMix = mapOf("MCQ" to (1.0 - natShare - msqShare).coerceAtLeast(0.0), "MSQ" to msqShare.toDouble(), "NAT" to natShare.toDouble()),
        )
    }

    fun kindFor(m: String) = if (m == "full_mock") "full" else m

    fun generate() {
        error = null
        status = JobStatus(id = "", status = "running", stage = "starting")
        job = scope.launch {
            try {
                val paper = runGeneration(container, request()) { status = it }
                val id = repo.importPaper(paper, kind = kindFor(mode))
                status = null
                openReport(id)
            } catch (e: Exception) {
                status = null
                error = e.message ?: e.toString()
            }
        }
    }

    // offline fallback: assemble from questions already on the phone
    fun offline() {
        scope.launch {
            val bank = repo.bankQuestions()
            val r = request()
            val n = if (mode == "full_mock") 65 else r.questionCount ?: 12
            val subjectFilter = if (mode == "weak_area") weak.map { it.substringBefore('.') }.toSet() else r.subjects?.toSet().orEmpty()
            val qs = OfflineAssembler.assemble(
                bank,
                OfflineAssembler.Spec(
                    subjects = subjectFilter,
                    topics = r.topics?.toSet().orEmpty(),
                    count = n,
                    difficulty = r.difficulty,
                    preferTopics = weak,
                    exclude = if (mode == "daily") repo.attemptedQuestionIds() else emptySet(),
                ),
            )
            if (qs.isEmpty()) { error = "No matching questions on this phone yet."; return@launch }
            val title = MODES.first { it.first == mode }.second + " (offline)"
            val id = repo.createOfflineTest(title, kindFor(mode), qs, OfflineAssembler.durationMin(qs))
            openReport(id)
        }
    }

    ScreenScaffold("AI Live Mock Generator", onBack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipRow(MODES.map { it.first }, mode, { k -> MODES.first { it.first == k }.second }) { mode = it }
            SectionCard("How each paper is made") {
                Text(
                    "Blueprint agent plans the paper -> 2 setter agents draft every question independently -> a review agent picks and improves the best draft -> " +
                        "3 solver agents solve it blind (different models and methods) -> answers must agree, or an adjudicator re-solves the dispute -> " +
                        "moderator checks syllabus, ambiguity and balance -> code re-checks NAT values, duplicates and the GATE pattern.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (mode) {
                "full_mock" -> Text("65 questions, 100 marks, 180 minutes. GA 15 marks + Technical 85 marks, weightage predicted from PYQ trends.")
                "daily" -> Text("12 new questions, weighted to your weak topics and predicted topics.")
                "weak_area" -> if (weak.isEmpty()) InfoBanner("Take a few tests first: weak topics come from your mistakes (topics below 60% strength).")
                else Text("Weak topics: " + weak.joinToString { repo.syllabus.topicName(it) })
                "subject", "custom" -> SubjectPicker(container, subjects.value) { subjects.value = it }
                "topic" -> TopicPicker(container, topics.value) { topics.value = it }
            }
            if (mode in listOf("subject", "topic", "weak_area", "custom")) {
                Text("Questions: ${count.toInt()}")
                Slider(count, { count = it }, valueRange = 5f..65f, steps = 59)
            }
            if (mode == "custom") {
                Text("Difficulty")
                ChipRow(listOf("Mixed", "Easy", "Medium", "Hard"), difficulty, { it }) { difficulty = it }
                Text("NAT share: ${(natShare * 100).toInt()}%   MSQ share: ${(msqShare * 100).toInt()}%")
                Slider(natShare, { natShare = it.coerceAtMost(1f - msqShare) }, valueRange = 0f..1f)
                Slider(msqShare, { msqShare = it.coerceAtMost(1f - natShare) }, valueRange = 0f..1f)
            }
            val ready = when (mode) {
                "subject" -> subjects.value.isNotEmpty()
                "topic" -> topics.value.isNotEmpty()
                "weak_area" -> weak.isNotEmpty()
                else -> true
            }
            Button(onClick = ::generate, enabled = ready && status == null, modifier = Modifier.fillMaxWidth()) { Text("Generate with AI agents") }
            OutlinedButton(onClick = ::offline, enabled = ready && status == null, modifier = Modifier.fillMaxWidth()) { Text("Assemble offline from saved questions") }
            error?.let { InfoBanner("Could not generate: $it. Check the backend URL in Settings, or use the offline option.") }
        }
    }

    status?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Setting your paper") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.stage.ifBlank { s.status })
                    LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                    s.log.takeLast(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Full mocks take several minutes: every question is solved by three agents.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); status = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectPicker(container: AppContainer, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        container.repo.syllabus.subjects.forEach { s ->
            FilterChip(selected = s.id in selected, onClick = { onChange(if (s.id in selected) selected - s.id else selected + s.id) }, label = { Text(s.name) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicPicker(container: AppContainer, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    var subject by remember { mutableStateOf(container.repo.syllabus.subjects[1].id) }
    ChipRow(container.repo.syllabus.subjects.map { it.id }, subject, { it }) { subject = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        container.repo.syllabus.subjects.first { it.id == subject }.topics.forEach { t ->
            FilterChip(selected = t.id in selected, onClick = { onChange(if (t.id in selected) selected - t.id else selected + t.id) }, label = { Text(t.name) })
        }
    }
}
