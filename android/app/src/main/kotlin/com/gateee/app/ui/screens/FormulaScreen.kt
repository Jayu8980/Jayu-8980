package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard

@Composable
fun FormulaScreen(container: AppContainer, onBack: () -> Unit) {
    val sheets = container.repo.formulas.sheets
    var subject by remember { mutableStateOf(sheets.firstOrNull()?.subjectId) }
    ScreenScaffold("Formula Sheets", onBack) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            ChipRow(sheets.map { it.subjectId }, subject, { id -> sheets.first { it.subjectId == id }.title }) { subject = it }
            val sheet = sheets.firstOrNull { it.subjectId == subject } ?: return@Column
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(sheet.sections) { sec ->
                    SectionCard(sec.heading) { sec.formulas.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
}
