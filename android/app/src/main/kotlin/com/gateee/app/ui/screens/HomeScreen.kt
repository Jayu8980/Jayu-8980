package com.gateee.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.R
import com.gateee.app.data.Settings
import com.gateee.app.ui.Routes
import com.gateee.app.ui.components.ScreenScaffold

private data class Tile(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

@Composable
fun HomeScreen(container: AppContainer, navigate: (String) -> Unit) {
    val streak by container.repo.streak().collectAsState(initial = 0 to 0)
    val settings by container.settings.flow.collectAsState(initial = Settings())
    val tiles = listOf(
        Tile("AI Full Mock", "65 Q - real pattern, multi-agent verified", Icons.Default.AutoAwesome, Routes.ai("full_mock")),
        Tile("Daily Live Practice", "12 fresh questions every day", Icons.Default.Bolt, Routes.ai("daily")),
        Tile("Weak-Area Mock", "built from your past mistakes", Icons.Default.TrackChanges, Routes.ai("weak_area")),
        Tile("Custom / Subject / Topic", "choose subjects, count, difficulty, type mix", Icons.Default.Quiz, Routes.ai("custom")),
        Tile("Mock Test Series", "20 topic + 10 subject + 10 full-length", Icons.Default.History, Routes.SERIES),
        Tile("PYQ Analysis", "2010-2026 trends, priorities, gap topics", Icons.Default.BarChart, Routes.ANALYSIS),
        Tile("PYQ Practice", "filter by year, subject, topic", Icons.Default.Quiz, Routes.PYQ),
        Tile("Progress & Analytics", "score graph, topic-strength map", Icons.Default.ShowChart, Routes.PROGRESS),
        Tile("Revision", "bookmarks, notes, wrong answers", Icons.Default.Bookmarks, Routes.REVISION),
        Tile("Formula Sheets", "every subject", Icons.Default.Functions, Routes.FORMULAS),
    )
    ScreenScaffold(stringResource(R.string.app_name), onBack = null, actions = {
        IconButton(onClick = { navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalFireDepartment, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${streak.first}-day streak", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Today: ${streak.second}/${settings.dailyTarget} questions", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(tiles.size) { i ->
                val t = tiles[i]
                Card(Modifier.fillMaxWidth().clickable { navigate(t.route) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}
