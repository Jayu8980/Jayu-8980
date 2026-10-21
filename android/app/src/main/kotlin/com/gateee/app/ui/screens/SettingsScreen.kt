package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gateee.app.AppContainer
import com.gateee.app.data.Settings
import com.gateee.app.data.ThemeMode
import com.gateee.app.reminders.ReminderWorker
import com.gateee.app.ui.components.ChipRow
import com.gateee.app.ui.components.InfoBanner
import com.gateee.app.ui.components.ScreenScaffold
import com.gateee.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<Settings?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { s = container.settings.current() }
    val cur = s ?: return

    fun save(next: Settings) {
        s = next
        scope.launch {
            container.settings.update { next }
            ReminderWorker.schedule(context, next.reminderHour, next.reminderMinute, next.remindersOn)
        }
    }

    ScreenScaffold("Settings", onBack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Appearance") {
                ChipRow(ThemeMode.entries, cur.theme, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { save(cur.copy(theme = it)) }
            }
            SectionCard("AI backend") {
                Text("The AI agents run on your backend server, which holds the LLM API key. The app never stores the key.")
                OutlinedTextField(cur.backendUrl, { s = cur.copy(backendUrl = it) }, Modifier.fillMaxWidth(), label = { Text("Backend URL") }, singleLine = true)
                OutlinedTextField(cur.clientToken, { s = cur.copy(clientToken = it) }, Modifier.fillMaxWidth(), label = { Text("App token (APP_CLIENT_TOKEN)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(cur) }) { Text("Save") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.settings.update { cur }
                            message = runCatching { container.backend.health() }.fold({ "Connected: $it" }, { "Not reachable: ${it.message}" })
                        }
                    }) { Text("Test connection") }
                }
                message?.let { InfoBanner(it) }
            }
            SectionCard("Daily streak and reminders") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily reminder", Modifier.weight(1f))
                    Switch(cur.remindersOn, { save(cur.copy(remindersOn = it)) })
                }
                Text("Reminder time: %02d:%02d".format(cur.reminderHour, cur.reminderMinute))
                Slider(cur.reminderHour.toFloat(), { s = cur.copy(reminderHour = it.toInt()) }, valueRange = 5f..23f, steps = 17, onValueChangeFinished = { s?.let(::save) })
                Text("Daily target: ${cur.dailyTarget} questions")
                Slider(cur.dailyTarget.toFloat(), { s = cur.copy(dailyTarget = it.toInt()) }, valueRange = 5f..65f, steps = 11, onValueChangeFinished = { s?.let(::save) })
            }
        }
    }
}
