package com.autotube.ai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.ui.AppPermissions
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.SectionTitle
import com.autotube.ai.ui.components.findActivity
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Launchers and actions the sections need from the Activity side. */
class SettingsActions(
    val pickModel: () -> Unit,
    val pickMusic: () -> Unit,
    val enableGallery: () -> Unit,
    val signOut: () -> Unit,
)

/** Settings: keys, providers, voice, music, visuals, look, defaults, quality, device, YouTube. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode, result.data)
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importMusic(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) {
            viewModel.update { it.copy(useGalleryMedia = true) }
        } else {
            scope.launch { snackbar.showSnackbar("Photo and video access was denied, so your gallery can't be used.") }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.LaunchConsent -> try {
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                } catch (e: Exception) {
                    scope.launch { snackbar.showSnackbar("Could not open Google sign-in.") }
                }
                is SettingsEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val actions = remember(viewModel) {
        SettingsActions(
            pickModel = { modelLauncher.launch(arrayOf("*/*")) },
            pickMusic = { musicLauncher.launch(arrayOf("audio/*")) },
            enableGallery = {
                if (AppPermissions.hasGallery(context)) {
                    viewModel.update { it.copy(useGalleryMedia = true) }
                } else {
                    galleryLauncher.launch(AppPermissions.galleryPermissions())
                }
            },
            signOut = { viewModel.signOut(context.findActivity()) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Everything works offline without keys. Add keys to unlock cloud quality.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApiKeysSection(state, viewModel)
            AiProvidersSection(state, viewModel, actions)
            VoiceSection(state, viewModel)
            MusicSection(state, viewModel, actions)
            VisualsSection(state, viewModel, actions)
            LookSection(state, viewModel)
            DefaultsSection(state, viewModel)
            QualitySection(state, viewModel)
            DeviceSection(state, viewModel)
            YouTubeSection(state, viewModel, actions)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Collapsible settings card. */
@Composable
internal fun SettingsSection(
    title: String,
    icon: ImageVector,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(title, icon = icon, subtitle = summary, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 12.dp), content = content)
        }
    }
}
