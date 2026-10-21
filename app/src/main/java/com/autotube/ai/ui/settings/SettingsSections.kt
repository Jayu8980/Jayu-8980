package com.autotube.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.core.model.LlmProviderType
import com.autotube.ai.core.model.MusicStyle
import com.autotube.ai.core.model.SttProviderType
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.UploadMode
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.ui.components.ChoiceChips
import com.autotube.ai.ui.components.CommitTextField
import com.autotube.ai.ui.components.FieldLabel
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.Pill
import com.autotube.ai.ui.components.RadioRow
import com.autotube.ai.ui.components.SegmentedChoice
import com.autotube.ai.ui.components.SimpleDropdown
import com.autotube.ai.ui.components.SliderRow
import com.autotube.ai.ui.components.SwitchRow
import com.autotube.ai.ui.components.ThinProgressBar
import com.autotube.ai.ui.components.UiIntents
import com.autotube.ai.ui.components.label
import com.autotube.ai.ui.components.nativeLabel
import com.autotube.ai.ui.theme.AutoTubeColors
import kotlin.math.roundToInt

private val OpenAiVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")

/** Where to get each key; null when there is no public sign-up page. */
private fun ApiKey.signupUrl(): String? = when (this) {
    ApiKey.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
    ApiKey.OPENAI -> "https://platform.openai.com/api-keys"
    ApiKey.ELEVENLABS -> "https://elevenlabs.io/app/settings/api-keys"
    ApiKey.PEXELS -> "https://www.pexels.com/api/"
    ApiKey.PIXABAY -> "https://pixabay.com/api/docs/"
    ApiKey.YOUTUBE_DATA -> "https://console.cloud.google.com/apis/library/youtube.googleapis.com"
    ApiKey.REPLICATE -> "https://replicate.com/account/api-tokens"
    ApiKey.CUSTOM_VIDEO -> null
}

private fun ApiKey.usage(): String = when (this) {
    ApiKey.ANTHROPIC -> "Research, script, editing plan, quality check"
    ApiKey.OPENAI -> "GPT script, TTS voice, Whisper captions, images"
    ApiKey.ELEVENLABS -> "Premium voiceover"
    ApiKey.PEXELS -> "Free stock videos and photos"
    ApiKey.PIXABAY -> "Free stock videos and photos"
    ApiKey.YOUTUBE_DATA -> "Trending topic research"
    ApiKey.REPLICATE -> "AI video and AI images"
    ApiKey.CUSTOM_VIDEO -> "Your own text-to-video endpoint"
}

// ---------------------------------------------------------------- API keys

@Composable
internal fun ApiKeysSection(state: SettingsUiState, vm: SettingsViewModel) {
    val saved = state.keysPresent.size
    SettingsSection("API keys", Icons.Rounded.VpnKey, "$saved of ${ApiKey.entries.size} saved · stored encrypted on this phone", initiallyExpanded = saved == 0) {
        ApiKey.entries.forEach { key ->
            ApiKeyField(
                key = key,
                value = state.keyDrafts[key].orEmpty(),
                saved = key in state.keysPresent,
                onChange = { vm.onKeyDraftChange(key, it) },
                onFocusLost = { vm.saveKey(key) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = vm::saveAllKeys, modifier = Modifier.fillMaxWidth()) { Text("Save keys") }
    }
}

@Composable
private fun ApiKeyField(key: ApiKey, value: String, saved: Boolean, onChange: (String) -> Unit, onFocusLost: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var visible by rememberSaveable(key.name) { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key.label, style = MaterialTheme.typography.bodyLarge)
                Text(key.usage(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(if (saved) "Saved" else "Missing", if (saved) AutoTubeColors.Green else AutoTubeColors.Grey)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(key.hint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (visible) "Hide" else "Show")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (it.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onFocusLost()
                    }
                },
        )
        val url = key.signupUrl()
        if (url != null) {
            TextButton(onClick = { UiIntents.openUrl(context, url) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Get a key")
            }
        }
    }
}

// ---------------------------------------------------------------- AI providers

@Composable
internal fun AiProvidersSection(state: SettingsUiState, vm: SettingsViewModel, actions: SettingsActions) {
    val s = state.settings
    SettingsSection("AI providers", Icons.Rounded.Psychology, if (s.forceOffline) "Fully offline" else s.llmProvider.label) {
        SwitchRow(
            title = "Run fully offline",
            subtitle = "Use only on-device voice, model/templates, music and visuals, even with keys",
            checked = s.forceOffline,
            onCheckedChange = { checked -> vm.update { it.copy(forceOffline = checked) } },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        FieldLabel("Script & research model")
        LlmProviderType.entries.forEach { type ->
            val hint = when (type) {
                LlmProviderType.CLAUDE -> if (ApiKey.ANTHROPIC in state.keysPresent) "Key saved" else "Needs a Claude key"
                LlmProviderType.OPENAI -> if (ApiKey.OPENAI in state.keysPresent) "Key saved" else "Needs an OpenAI key"
                LlmProviderType.ON_DEVICE -> state.onDeviceModelName?.let { "Model: $it" } ?: "Import a .task model below"
                LlmProviderType.OFFLINE_TEMPLATES -> "Works everywhere, simpler scripts"
            }
            RadioRow(
                title = type.label,
                subtitle = hint,
                selected = s.llmProvider == type,
                onClick = { vm.update { it.copy(llmProvider = type) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        CommitTextField(
            value = s.claudeModel,
            onCommit = { v -> vm.update { it.copy(claudeModel = v.trim().ifBlank { "claude-opus-5-5" }) } },
            label = "Claude model",
            placeholder = "claude-opus-5-5",
        )
        Spacer(Modifier.height(8.dp))
        CommitTextField(
            value = s.openAiModel,
            onCommit = { v -> vm.update { it.copy(openAiModel = v.trim().ifBlank { "gpt-4o" }) } },
            label = "OpenAI model",
            placeholder = "gpt-4o",
        )
        FieldLabel("On-device model (MediaPipe .task)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(state.onDeviceModelName ?: "No model imported", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (state.onDeviceModelName != null) {
                TextButton(onClick = vm::removeModel, enabled = state.local.modelImportProgress == null) { Text("Remove") }
            }
        }
        val importing = state.local.modelImportProgress
        if (importing != null) {
            Spacer(Modifier.height(8.dp))
            Text("Importing… ${Format.percent(importing)}", style = MaterialTheme.typography.bodySmall)
            ThinProgressBar(importing, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = actions.pickModel, enabled = importing == null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import on-device model (.task)")
        }
    }
}

// ---------------------------------------------------------------- Voice

@Composable
internal fun VoiceSection(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    SettingsSection("Voice", Icons.Rounded.RecordVoiceOver, s.ttsProvider.label) {
        FieldLabel("Text-to-speech")
        TtsProviderType.entries.forEach { type ->
            val hint = when (type) {
                TtsProviderType.ELEVENLABS -> if (ApiKey.ELEVENLABS in state.keysPresent) "Key saved" else "Needs an ElevenLabs key"
                TtsProviderType.OPENAI -> if (ApiKey.OPENAI in state.keysPresent) "Key saved" else "Needs an OpenAI key"
                TtsProviderType.ON_DEVICE -> "Free, works offline"
            }
            RadioRow(title = type.label, subtitle = hint, selected = s.ttsProvider == type, onClick = { vm.update { it.copy(ttsProvider = type) } })
        }
        Spacer(Modifier.height(8.dp))
        SimpleDropdown(
            label = "OpenAI voice",
            options = OpenAiVoices,
            selected = s.openAiVoice,
            optionLabel = { it.replaceFirstChar { c -> c.uppercase() } },
            onSelect = { voice -> vm.update { it.copy(openAiVoice = voice) } },
        )
        Spacer(Modifier.height(12.dp))
        CommitTextField(
            value = s.elevenLabsVoiceId,
            onCommit = { v -> vm.update { it.copy(elevenLabsVoiceId = v.trim().ifBlank { "21m00Tcm4TlvDq8ikWAM" }) } },
            label = "ElevenLabs voice ID",
            supportingText = "Copy it from the ElevenLabs Voice Library",
        )
        FieldLabel("On-device voice (${s.defaultLanguage.nativeLabel})")
        val voices = state.local.voices
        val selectedName = s.onDeviceVoiceName
        val options: List<VoiceOption?> = listOf<VoiceOption?>(null) + voices
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimpleDropdown(
                options = options,
                selected = voices.firstOrNull { it.name == selectedName },
                optionLabel = { it?.label ?: if (selectedName != null && voices.none { v -> v.name == selectedName }) "Engine default (saved: $selectedName)" else "Engine default" },
                onSelect = { option -> vm.update { it.copy(onDeviceVoiceName = option?.name) } },
                enabled = !state.local.voicesLoading,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.loadVoices(s.defaultLanguage) }) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload voices") }
        }
        Text(
            when {
                state.local.voicesLoading -> "Looking for installed voices…"
                voices.isEmpty() -> "No ${s.defaultLanguage.label} voices found. Install one in Android Settings › Text-to-speech."
                else -> "${voices.size} voices installed"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        FieldLabel("Caption timing (speech-to-text)")
        SttProviderType.entries.forEach { type ->
            val hint = when (type) {
                SttProviderType.WHISPER -> if (ApiKey.OPENAI in state.keysPresent) "Key saved" else "Needs an OpenAI key"
                SttProviderType.ON_DEVICE -> "Free, works offline"
            }
            RadioRow(title = type.label, subtitle = hint, selected = s.sttProvider == type, onClick = { vm.update { it.copy(sttProvider = type) } })
        }
    }
}

// ---------------------------------------------------------------- Music

@Composable
internal fun MusicSection(state: SettingsUiState, vm: SettingsViewModel, actions: SettingsActions) {
    val s = state.settings
    SettingsSection("Music & sound", Icons.Rounded.LibraryMusic, s.musicStyle.label) {
        FieldLabel("Music style")
        ChoiceChips(
            options = MusicStyle.entries.toList(),
            selected = s.musicStyle,
            optionLabel = { it.label },
            onSelect = { style -> vm.update { it.copy(musicStyle = style) } },
        )
        SliderRow(
            title = "Music volume",
            value = s.musicVolume.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> vm.update { it.copy(musicVolume = v) } },
        )
        SwitchRow("Sound effects", s.soundEffects, { c -> vm.update { it.copy(soundEffects = c) } }, subtitle = "Whooshes, risers and hits on cuts")
        SwitchRow("Room ambience", s.ambientRoomTone, { c -> vm.update { it.copy(ambientRoomTone = c) } }, subtitle = "Subtle background tone so silence feels natural")
        FieldLabel("Your own music, per mood")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimpleDropdown(
                options = Emotion.entries.toList(),
                selected = state.local.musicImportMood,
                optionLabel = { mood -> "${mood.label} (${state.local.importedMusic[mood] ?: 0})" },
                onSelect = vm::setMusicImportMood,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = actions.pickMusic, enabled = state.local.musicImportProgress == null) { Text("Import") }
        }
        state.local.musicImportProgress?.let { ThinProgressBar(it, modifier = Modifier.padding(top = 8.dp)) }
        val mood = state.local.musicImportMood
        val count = state.local.importedMusic[mood] ?: 0
        if (count > 0) {
            TextButton(onClick = { vm.clearImportedMusic(mood) }) { Text("Remove $count ${mood.label.lowercase()} track${if (count > 1) "s" else ""}") }
        }
    }
}

// ---------------------------------------------------------------- Visuals

@Composable
internal fun VisualsSection(state: SettingsUiState, vm: SettingsViewModel, actions: SettingsActions) {
    val s = state.settings
    SettingsSection("Visuals", Icons.Rounded.Image, if (s.useStockFootage) "Stock footage + fallbacks" else "AI / on-device visuals") {
        SwitchRow(
            "Stock footage",
            s.useStockFootage,
            { c -> vm.update { it.copy(useStockFootage = c) } },
            subtitle = "Pexels and Pixabay (needs a key)",
        )
        Spacer(Modifier.height(8.dp))
        SimpleDropdown(
            label = "Text-to-video",
            options = TextToVideoProviderType.entries.toList(),
            selected = s.textToVideoProvider,
            optionLabel = { it.label },
            onSelect = { t -> vm.update { it.copy(textToVideoProvider = t) } },
        )
        when (s.textToVideoProvider) {
            TextToVideoProviderType.NONE -> Unit
            TextToVideoProviderType.REPLICATE -> {
                Spacer(Modifier.height(8.dp))
                CommitTextField(
                    value = s.replicateVideoModel,
                    onCommit = { v -> vm.update { it.copy(replicateVideoModel = v.trim().ifBlank { "minimax/video-01" }) } },
                    label = "Replicate video model",
                )
            }
            TextToVideoProviderType.CUSTOM -> {
                Spacer(Modifier.height(8.dp))
                CommitTextField(
                    value = s.customVideoEndpoint,
                    onCommit = { v -> vm.update { it.copy(customVideoEndpoint = v.trim()) } },
                    label = "Custom endpoint URL",
                    placeholder = "https://…",
                    keyboardType = KeyboardType.Uri,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SimpleDropdown(
            label = "AI images",
            options = ImageGenProviderType.entries.toList(),
            selected = s.imageGenProvider,
            optionLabel = { it.label },
            onSelect = { t -> vm.update { it.copy(imageGenProvider = t) } },
        )
        when (s.imageGenProvider) {
            ImageGenProviderType.NONE -> Unit
            ImageGenProviderType.OPENAI -> {
                Spacer(Modifier.height(8.dp))
                CommitTextField(
                    value = s.openAiImageModel,
                    onCommit = { v -> vm.update { it.copy(openAiImageModel = v.trim().ifBlank { "gpt-image-1" }) } },
                    label = "OpenAI image model",
                )
            }
            ImageGenProviderType.REPLICATE -> {
                Spacer(Modifier.height(8.dp))
                CommitTextField(
                    value = s.replicateImageModel,
                    onCommit = { v -> vm.update { it.copy(replicateImageModel = v.trim().ifBlank { "black-forest-labs/flux-schnell" }) } },
                    label = "Replicate image model",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Use my gallery media",
            s.useGalleryMedia,
            { checked -> if (checked) actions.enableGallery() else vm.update { it.copy(useGalleryMedia = false) } },
            subtitle = "Lets AutoTube pick matching photos and videos from your phone",
        )
        SwitchRow(
            "Vision check",
            s.visionCheck,
            { c -> vm.update { it.copy(visionCheck = c) } },
            subtitle = "An AI model rates whether each clip matches the narration",
        )
        SliderRow(
            title = "Minimum match score",
            value = s.visionMinScore.toFloat().coerceIn(1f, 10f),
            valueRange = 1f..10f,
            steps = 8,
            enabled = s.visionCheck,
            valueLabel = { "${it.roundToInt()}/10" },
            onCommit = { v -> vm.update { it.copy(visionMinScore = v.roundToInt()) } },
        )
    }
}

// ---------------------------------------------------------------- Look

@Composable
internal fun LookSection(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    SettingsSection("Look", Icons.Rounded.Palette, s.captionStyle.label) {
        SwitchRow("Colour grading", s.colorGrading, { c -> vm.update { it.copy(colorGrading = c) } }, subtitle = "Warm or cool grade that matches each scene's mood")
        SwitchRow("Film grain", s.filmGrain, { c -> vm.update { it.copy(filmGrain = c) } })
        SwitchRow("Vignette", s.vignette, { c -> vm.update { it.copy(vignette = c) } })
        SwitchRow("Handheld camera shake", s.handheldShake, { c -> vm.update { it.copy(handheldShake = c) } }, subtitle = "Very light, so stills feel filmed")
        FieldLabel("Caption style")
        ChoiceChips(
            options = CaptionStyle.entries.toList(),
            selected = s.captionStyle,
            optionLabel = { it.label },
            onSelect = { c -> vm.update { it.copy(captionStyle = c) } },
        )
        SwitchRow("Emojis in captions", s.emojiInCaptions, { c -> vm.update { it.copy(emojiInCaptions = c) } }, enabled = s.captionStyle != CaptionStyle.OFF)
    }
}

// ---------------------------------------------------------------- Defaults

@Composable
internal fun DefaultsSection(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    SettingsSection("Defaults", Icons.Rounded.Tune, "${s.defaultFormat.label} · ${s.defaultLanguage.nativeLabel}") {
        FieldLabel("Video type")
        SegmentedChoice(
            options = listOf(VideoFormat.LONG_FORM, VideoFormat.SHORTS),
            selected = s.defaultFormat,
            optionLabel = { it.label },
            onSelect = { f -> vm.update { it.copy(defaultFormat = f) } },
        )
        FieldLabel("Shorts length")
        ChoiceChips(
            options = listOf(15, 30, 45, 60),
            selected = s.defaultShortsSec,
            optionLabel = { "$it s" },
            onSelect = { v -> vm.update { it.copy(defaultShortsSec = v) } },
        )
        SliderRow(
            title = "Long-form length",
            value = (s.defaultLongFormSec / 60).coerceIn(1, 20).toFloat(),
            valueRange = 1f..20f,
            steps = 18,
            valueLabel = { "${it.roundToInt()} min" },
            onCommit = { v -> vm.update { it.copy(defaultLongFormSec = v.roundToInt() * 60) } },
        )
        FieldLabel("Language")
        ChoiceChips(
            options = AppLanguage.entries.toList(),
            selected = s.defaultLanguage,
            optionLabel = { it.nativeLabel },
            onSelect = { l -> vm.update { it.copy(defaultLanguage = l) } },
        )
        Spacer(Modifier.height(8.dp))
        CommitTextField(
            value = s.regionCode,
            onCommit = { v -> vm.update { it.copy(regionCode = v.trim().uppercase().take(2).ifBlank { "US" }) } },
            label = "Region for trend research",
            supportingText = "Two-letter country code, e.g. US or IN",
        )
        SwitchRow(
            "Review mode",
            s.reviewMode,
            { c -> vm.update { it.copy(reviewMode = c) } },
            subtitle = "Pick the topic yourself and approve before uploading",
        )
    }
}

// ---------------------------------------------------------------- Quality

@Composable
internal fun QualitySection(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    SettingsSection("Quality", Icons.Rounded.HighQuality, if (s.qualityCheck) "Auto quality check on" else "Quality check off") {
        SwitchRow(
            "Automatic quality check",
            s.qualityCheck,
            { c -> vm.update { it.copy(qualityCheck = c) } },
            subtitle = "Finds black frames, clipping, caption drift and fixes them",
        )
        FieldLabel("Max fix passes")
        SegmentedChoice(
            options = listOf(0, 1, 2),
            selected = s.maxQcPasses.coerceIn(0, 2),
            optionLabel = { it.toString() },
            onSelect = { v -> vm.update { it.copy(maxQcPasses = v) } },
        )
        SliderRow(
            title = "Minimum human-feel score",
            value = s.minHumanFeelScore.toFloat().coerceIn(1f, 10f),
            valueRange = 1f..10f,
            steps = 8,
            enabled = s.qualityCheck,
            valueLabel = { "${it.roundToInt()}/10" },
            onCommit = { v -> vm.update { it.copy(minHumanFeelScore = v.roundToInt()) } },
        )
    }
}

// ---------------------------------------------------------------- Device

@Composable
internal fun DeviceSection(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    val used = state.local.storageUsedBytes
    SettingsSection("Device", Icons.Rounded.PhoneAndroid, used?.let { "Using ${Format.bytes(it)}" } ?: "Storage and network") {
        SwitchRow("Wi-Fi only", s.wifiOnly, { c -> vm.update { it.copy(wifiOnly = c) } }, subtitle = "Downloads and uploads wait for Wi-Fi")
        SliderRow(
            title = "Minimum free storage",
            value = s.minFreeStorageMb.toFloat().coerceIn(500f, 5000f),
            valueRange = 500f..5000f,
            steps = 17,
            valueLabel = { "${(it / 250f).roundToInt() * 250} MB" },
            onCommit = { v -> vm.update { it.copy(minFreeStorageMb = (v / 250f).roundToInt() * 250) } },
        )
        SwitchRow(
            "Delete temporary files after export",
            s.deleteTempAfterExport,
            { c -> vm.update { it.copy(deleteTempAfterExport = c) } },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Storage used by AutoTube", style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append(used?.let { Format.bytes(it) } ?: "Calculating…")
                        state.local.freeBytes?.let { append(" · ${Format.bytes(it)} free on phone") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::refreshStorage) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = vm::clearAllTemp, enabled = !state.local.clearingTemp, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.local.clearingTemp) "Clearing…" else "Clear all temporary files")
        }
    }
}

// ---------------------------------------------------------------- YouTube

@Composable
internal fun YouTubeSection(state: SettingsUiState, vm: SettingsViewModel, actions: SettingsActions) {
    val s = state.settings
    val account = state.signedInLabel
    SettingsSection("YouTube", Icons.Rounded.OndemandVideo, account ?: "Not signed in") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (account != null) Icons.Rounded.CheckCircle else Icons.Rounded.AccountCircle,
                contentDescription = null,
                tint = if (account != null) AutoTubeColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account ?: "Google account", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (account != null) "Ready to upload" else "Sign in to upload videos to your channel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (account == null) {
            Button(onClick = vm::signIn, enabled = !state.local.signingIn, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.local.signingIn) "Signing in…" else "Sign in with Google")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::signIn, enabled = !state.local.signingIn, modifier = Modifier.weight(1f)) {
                    Text("Check access")
                }
                OutlinedButton(onClick = actions.signOut, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sign out")
                }
            }
        }
        if (s.googleAccountEmail == null && !s.reviewMode && s.autoUploadWhenReviewOff) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = AutoTubeColors.Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Automatic upload needs a signed-in account.", style = MaterialTheme.typography.bodySmall)
            }
        }
        FieldLabel("Default upload mode")
        UploadMode.entries.forEach { mode ->
            RadioRow(title = mode.label, selected = s.defaultUploadMode == mode, onClick = { vm.update { it.copy(defaultUploadMode = mode) } })
        }
        SwitchRow(
            "Made for kids",
            s.madeForKids,
            { c -> vm.update { it.copy(madeForKids = c) } },
            subtitle = "Required by YouTube for content aimed at children",
        )
    }
}
