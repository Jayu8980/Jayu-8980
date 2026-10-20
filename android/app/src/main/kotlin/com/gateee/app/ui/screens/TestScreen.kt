package com.gateee.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gateee.app.AppContainer
import com.gateee.app.ui.components.EmptyState
import com.gateee.app.ui.components.formatSec
import com.gateee.app.ui.theme.PaletteColors
import com.gateee.core.QStatus
import com.gateee.core.QType
import com.gateee.core.Question

private val LETTERS = listOf("A", "B", "C", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(container: AppContainer, testId: String, onBack: () -> Unit, onSubmitted: (Long) -> Unit) {
    val vm: TestViewModel = viewModel(key = "test-$testId", factory = viewModelFactory { initializer { TestViewModel(container, testId) } })
    var showPalette by remember { mutableStateOf(false) }
    var showCalc by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(vm.submittedAttemptId) { vm.submittedAttemptId?.let(onSubmitted) }
    BackHandler { confirmLeave = true }

    val l = vm.loaded
    if (l == null) { EmptyState("Loading test..."); return }
    if (l.questions.isEmpty()) { EmptyState("This test has no questions."); return }
    val q = l.questions[vm.current]
    val sections = l.questions.map { it.section }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(l.test.title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        val low = vm.remainingSec < 300
                        Text("Time left ${formatSec(vm.remainingSec)}", color = if (low) PaletteColors.notAnswered else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showCalc = true }) { Icon(Icons.Default.Calculate, "Virtual calculator") }
                    IconButton(onClick = { showPalette = true }) { Icon(Icons.Default.Apps, "Question palette") }
                    TextButton(onClick = { confirmSubmit = true }) { Text("Submit") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = vm::markForReviewAndNext, Modifier.weight(1.4f)) { Text("Mark for Review & Next", maxLines = 1) }
                        OutlinedButton(onClick = vm::clearResponse, Modifier.weight(1f)) { Text("Clear", maxLines = 1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { vm.go(vm.current - 1) }, Modifier.weight(1f), enabled = vm.current > 0) { Text("Back") }
                        Button(onClick = vm::saveAndNext, Modifier.weight(1.4f)) { Text("Save & Next") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (sections.size > 1) {
                TabRow(selectedTabIndex = sections.indexOf(q.section)) {
                    sections.forEach { s ->
                        Tab(selected = q.section == s, onClick = { vm.go(l.questions.indexOfFirst { it.section == s }) }, text = { Text(if (s == "GA") "General Aptitude" else "Technical") })
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val neg = if (q.type == QType.MCQ) "-${if (q.marks == 1) "1/3" else "2/3"}" else "no negative"
                Text("Question ${vm.current + 1} of ${l.questions.size}  |  ${q.type}  |  +${q.marks}, $neg", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(q.question, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                val draft = vm.drafts[q.id]
                when (q.type) {
                    QType.MCQ -> q.options.forEachIndexed { i, opt ->
                        val letter = LETTERS[i]
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = draft == letter, onClick = { vm.setDraft(letter) }, role = Role.RadioButton).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = draft == letter, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text("($letter) $opt")
                        }
                    }
                    QType.MSQ -> {
                        val chosen = draft.orEmpty().split(",").filter { it.isNotBlank() }.toSet()
                        q.options.forEachIndexed { i, opt ->
                            val letter = LETTERS[i]
                            val toggled = { val n = if (letter in chosen) chosen - letter else chosen + letter; vm.setDraft(n.sorted().joinToString(",").ifBlank { null }) }
                            Row(Modifier.fillMaxWidth().clickable(onClick = toggled).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = letter in chosen, onCheckedChange = { toggled() })
                                Spacer(Modifier.width(8.dp))
                                Text("($letter) $opt")
                            }
                        }
                    }
                    QType.NAT -> NumericKeypad(draft.orEmpty()) { vm.setDraft(it.ifBlank { null }) }
                }
            }
        }
    }

    if (showPalette) {
        ModalBottomSheet(onDismissRequest = { showPalette = false }) {
            PalettePanel(vm, l.questions) { i -> vm.go(i); showPalette = false }
        }
    }
    if (showCalc) CalculatorDialog { showCalc = false }
    if (confirmSubmit) {
        val c = vm.counts()
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("Submit test?") },
            text = {
                Column {
                    Text("Answered: ${c.getValue(QStatus.ANSWERED) + c.getValue(QStatus.ANSWERED_MARKED)}")
                    Text("Not answered: ${c.getValue(QStatus.NOT_ANSWERED)}   Marked: ${c.getValue(QStatus.MARKED)}   Not visited: ${c.getValue(QStatus.NOT_VISITED)}")
                    Text("Time left: ${formatSec(vm.remainingSec)}")
                }
            },
            confirmButton = { Button(onClick = { confirmSubmit = false; vm.submit() }, enabled = !vm.submitting) { Text("Submit") } },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Continue test") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the test?") },
            text = { Text("Your answers are saved and the clock keeps running, as in the real exam. You can resume from the paper.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.persist(); onBack() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PalettePanel(vm: TestViewModel, questions: List<Question>, onPick: (Int) -> Unit) {
    val c = vm.counts()
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(PaletteColors.answered, "Answered ${c.getValue(QStatus.ANSWERED)}")
            LegendDot(PaletteColors.notAnswered, "Not answered ${c.getValue(QStatus.NOT_ANSWERED)}")
            LegendDot(PaletteColors.marked, "Marked ${c.getValue(QStatus.MARKED)}")
            LegendDot(PaletteColors.marked, "Answered & marked ${c.getValue(QStatus.ANSWERED_MARKED)}", dot = true)
            LegendDot(PaletteColors.notVisited, "Not visited ${c.getValue(QStatus.NOT_VISITED)}")
        }
        LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.height(360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(questions) { i, q ->
                val st = vm.states[q.id]?.status ?: QStatus.NOT_VISITED
                PaletteCell(i + 1, st, current = i == vm.current) { onPick(i) }
            }
        }
    }
}

@Composable
private fun PaletteCell(n: Int, status: QStatus, current: Boolean, onClick: () -> Unit) {
    val color = when (status) {
        QStatus.ANSWERED -> PaletteColors.answered
        QStatus.NOT_ANSWERED -> PaletteColors.notAnswered
        QStatus.MARKED, QStatus.ANSWERED_MARKED -> PaletteColors.marked
        QStatus.NOT_VISITED -> PaletteColors.notVisited
    }
    val shape = if (status == QStatus.MARKED || status == QStatus.ANSWERED_MARKED) CircleShape else RoundedCornerShape(6.dp)
    Box(
        Modifier.size(44.dp).background(color, shape)
            .then(if (current) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
        if (status == QStatus.ANSWERED_MARKED) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).background(PaletteColors.answered, CircleShape))
    }
}

@Composable
private fun LegendDot(color: Color, label: String, dot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).background(color, if (dot) CircleShape else RoundedCornerShape(3.dp))) {
            if (dot) Box(Modifier.align(Alignment.BottomEnd).size(6.dp).background(PaletteColors.answered, CircleShape))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** NAT answers are typed on an on-screen numeric keypad only (as in GATE). */
@Composable
fun NumericKeypad(value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), tonalElevation = 2.dp) {
            Text(value.ifBlank { "Enter numeric answer" }, Modifier.padding(14.dp), style = MaterialTheme.typography.titleLarge,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        val keys = listOf(listOf("7", "8", "9", "⌫"), listOf("4", "5", "6", "-"), listOf("1", "2", "3", "."), listOf("0", "Clear"))
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    OutlinedButton(
                        onClick = {
                            onChange(
                                when (k) {
                                    "⌫" -> value.dropLast(1)
                                    "Clear" -> ""
                                    "-" -> if (value.startsWith("-")) value.drop(1) else "-$value"
                                    "." -> if ("." in value) value else value.ifBlank { "0" } + "."
                                    else -> if (value.length < 12) value + k else value
                                },
                            )
                        },
                        modifier = Modifier.weight(if (k == "Clear") 3f else 1f),
                    ) { Text(k) }
                }
            }
        }
    }
}
