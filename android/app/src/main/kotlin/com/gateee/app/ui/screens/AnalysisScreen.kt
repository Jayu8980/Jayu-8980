package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.HBarChart
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.LineChart
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import com.gateee.app.ui.components.StackedBars
import com.gateee.core.QType
import com.gateee.core.TopicStat

private val TABS = listOf("Subjects", "Trends", "Types", "Difficulty", "High Priority", "Gap Topics", "Repeated")

@Composable
fun AnalysisScreen(container: AppContainer, onBack: () -> Unit) {
    val repo = container.repo
    val a = remember { repo.analysis }
    val syl = repo.syllabus
    var tab by remember { mutableIntStateOf(0) }
    ScreenScaffold("PYQ Analysis", onBack) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (a.isDemo) {
                        Column(Modifier.padding(top = 12.dp)) {
                            InfoBanner("Showing SYNTHETIC demo tags. Add your tagged GATE EE PYQs as data/pyq_bank.json (see docs/PYQ_DATA.md) for real analysis.")
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        item {
                            SectionCard("Average marks per paper (5-year)") {
                                HBarChart(a.subjects.values.sortedByDescending { it.avg5 }.map { syl.subjectName(it.subjectId) to it.avg5 })
                            }
                        }
                        item {
                            SectionCard("Average marks per paper (10-year)") {
                                HBarChart(a.subjects.values.sortedByDescending { it.avg10 }.map { syl.subjectName(it.subjectId) to it.avg10 })
                            }
                        }
                        item {
                            SectionCard("Subject marks by year") {
                                a.subjects.values.sortedBy { it.subjectId }.forEach { s ->
                                    Text(syl.subjectName(s.subjectId), style = MaterialTheme.typography.labelLarge)
                                    Text(a.years.joinToString("  ") { y -> "${y % 100}:${s.marksByYear[y] ?: 0}" }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    1 -> {
                        item { TrendCard("Rising topics", a.rising.take(5), a.years) }
                        item { TrendCard("Falling topics", a.falling.take(5), a.years) }
                    }
                    2 -> item {
                        SectionCard("MCQ / MSQ / NAT split per year") {
                            StackedBars(a.years.map { y -> y.toString() to QType.entries.map { (a.typeSplitByYear[y]?.get(it) ?: 0).toDouble() } }, QType.entries.map { it.name })
                        }
                    }
                    3 -> item {
                        SectionCard("Difficulty distribution per paper") {
                            val keys = listOf("Easy", "Medium", "Hard")
                            StackedBars(a.years.map { y -> y.toString() to keys.map { (a.difficultyByYear[y]?.get(it) ?: 0).toDouble() } }, keys)
                        }
                    }
                    4 -> {
                        item { Text("Ranked by marks x frequency", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                        items(a.highPriority) { TopicRow(it) }
                    }
                    5 -> {
                        item { Text("Important topics not asked in the last 2-3 years", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                        if (a.gapTopics.isEmpty()) item { Text("No gap topics in this data.") }
                        items(a.gapTopics) { TopicRow(it) }
                    }
                    6 -> items(a.mostRepeatedConcepts) { c ->
                        SectionCard {
                            Text(c.concept, style = MaterialTheme.typography.titleSmall)
                            Text("${syl.topicName(c.topicId)} - asked ${c.count} times (${c.years.distinct().joinToString { (it % 100).toString().padStart(2, '0') }})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Column(Modifier.padding(bottom = 24.dp)) {} }
            }
        }
    }
}

@Composable
private fun TrendCard(title: String, topics: List<TopicStat>, years: List<Int>) {
    SectionCard(title) {
        if (topics.isEmpty()) Text("None in this data.")
        else {
            val recent = years.takeLast(8)
            LineChart(recent.map { it.toString() }, topics.map { t -> t.name to recent.map { (t.marksByYear[it] ?: 0).toDouble() } })
        }
    }
}

@Composable
private fun TopicRow(t: TopicStat) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                Text("${t.subjectId} - ${t.frequency} Qs, ${t.marks} marks, last ${t.lastAsked ?: "-"}, ${t.trend}", style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.width(80.dp)) {
                Text("${(t.predictedScore * 100).toInt()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("likelihood", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
