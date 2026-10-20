package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.data.JobStatus
import com.gateee.app.ui.Routes
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.core.OfflineAssembler
import com.gateee.core.SeriesTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(container: AppContainer, onBack: () -> Unit, navigate: (String) -> Unit) {
    val repo = container.repo
    val scope = rememberCoroutineScope()
    val ready by repo.seriesTests().collectAsState(initial = emptyList())
    val readyIds = ready.mapNotNull { it.seriesId }.toSet()
    var kind by remember { mutableStateOf("topic") }
    var status by remember { mutableStateOf<JobStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun generate(t: SeriesTest) {
        error = null
        status = JobStatus(id = "", status = "running", stage = "starting")
        job = scope.launch {
            try {
                val paper = runGeneration(container, t.request.copy(durationMin = t.durationMin)) { status = it }
                repo.importPaper(paper.copy(title = t.title, durationMin = t.durationMin), kind = t.kind, seriesId = t.id)
                status = null
                navigate(Routes.report(t.id))
            } catch (e: Exception) {
                status = null
                error = e.message
            }
        }
    }

    fun offline(t: SeriesTest) {
        scope.launch {
            val r = t.request
            val qs = OfflineAssembler.assemble(
                repo.bankQuestions(),
                OfflineAssembler.Spec(subjects = r.subjects?.toSet().orEmpty(), topics = r.topics?.toSet().orEmpty(), count = t.questions),
            )
            if (qs.isEmpty()) { error = "No saved questions match ${t.title} yet."; return@launch }
            repo.createOfflineTest("${t.title} (offline, ${qs.size} Q)", t.kind, qs, OfflineAssembler.durationMin(qs))
                .let { navigate(Routes.report(it)) }
        }
    }

    ScreenScaffold("Mock Test Series", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ChipRow(listOf("topic", "subject", "full"), kind, { mapOf("topic" to "20 Topic-wise", "subject" to "10 Subject-wise", "full" to "10 Full-length")[it]!! }) { kind = it }
            }
            error?.let { item { InfoBanner(it) } }
            items(repo.series.tests.filter { it.kind == kind }, key = { it.id }) { t ->
                SectionCard(t.title) {
                    Text("${t.questions} Q - ${t.marks} marks - ${t.durationMin} min", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.id in readyIds) {
                            Button(onClick = { navigate(Routes.report(t.id)) }) { Text("Open") }
                        } else {
                            Button(onClick = { generate(t) }, enabled = status == null) { Text("Generate with AI") }
                            OutlinedButton(onClick = { offline(t) }) { Text("Offline") }
                        }
                    }
                }
            }
        }
    }

    status?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Setting the paper") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.stage)
                    LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); status = null }) { Text("Cancel") } },
        )
    }
}
