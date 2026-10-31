package com.autotube.ai.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.autotube.ai.studio.Adjustments
import com.autotube.ai.studio.AudioLayer
import com.autotube.ai.studio.CanvasRatio
import com.autotube.ai.studio.ClipTransition
import com.autotube.ai.studio.FilterPreset
import com.autotube.ai.studio.FitMode
import com.autotube.ai.studio.StudioClip
import com.autotube.ai.studio.StudioColor
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.TextAnim
import com.autotube.ai.studio.TextFont
import com.autotube.ai.studio.TextLayer
import com.autotube.ai.studio.TextLook
import com.autotube.ai.ui.components.ChoiceChips
import com.autotube.ai.ui.components.FieldLabel
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.SliderRow
import com.autotube.ai.ui.components.SwitchRow
import com.autotube.ai.ui.theme.AutoTubeColors
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Frame shared by every tool panel: title, Done button and scrollable content. */
@Composable
fun PanelFrame(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AutoTubeColors.Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, contentDescription = "Done") }
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { content() }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun secs(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000f)

// ---------------------------------------------------------------------------------------- clip panels

private val SpeedPresets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

@Composable
fun SpeedPanel(clip: StudioClip, onChange: (Float) -> Unit, onDone: () -> Unit) {
    PanelFrame("Speed", onDone) {
        ChoiceChips(SpeedPresets, SpeedPresets.firstOrNull { it == clip.speed }, { "${it}x" }, onChange)
        SliderRow(
            title = "Custom speed",
            value = clip.speed,
            valueRange = 0.25f..4f,
            valueLabel = { String.format(Locale.US, "%.2fx", it) },
            onCommit = { onChange((it * 20).roundToInt() / 20f) },
            subtitle = "New length ${Format.clock(clip.durationMs)} (pitch is kept)",
        )
    }
}

@Composable
fun ImageDurationPanel(clip: StudioClip, onChange: (Long) -> Unit, onDone: () -> Unit) {
    PanelFrame("Duration", onDone) {
        ChoiceChips(listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 10_000L), clip.trimmedMs, { secs(it) }, onChange)
        SliderRow(
            title = "Photo duration",
            value = clip.trimmedMs / 1000f,
            valueRange = 0.5f..20f,
            valueLabel = { String.format(Locale.US, "%.1f s", it) },
            onCommit = { onChange((it * 10).roundToInt() * 100L) },
        )
    }
}

@Composable
fun VolumePanel(volume: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, title: String = "Volume") {
    PanelFrame(title, onDone) {
        if (!enabled) {
            Text("This clip has no sound.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@PanelFrame
        }
        SliderRow(title = "Volume", value = volume, valueRange = 0f..2f, valueLabel = ::pct, onCommit = { onChange((it * 100).roundToInt() / 100f) })
        SwitchRow(title = "Mute", checked = volume == 0f, onCheckedChange = { onChange(if (it) 0f else 1f) })
    }
}

@Composable
fun FiltersPanel(clip: StudioClip, onChange: (FilterPreset, Float) -> Unit, onApplyAll: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val request = remember(clip.path, clip.trimStartMs) {
        val b = ImageRequest.Builder(context).data(File(clip.path)).size(200)
        if (!clip.isImage) b.decoderFactory(VideoFrameDecoder.Factory()).videoFrameMillis(clip.trimStartMs)
        b.build()
    }
    PanelFrame("Filters", onDone) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPreset.entries.forEach { f ->
                val selected = clip.filter == f
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(f, if (f == FilterPreset.NONE) 1f else clip.filterStrength.takeIf { it > 0f } ?: 1f) }) {
                    AsyncImage(
                        model = request,
                        contentDescription = f.label,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (f == FilterPreset.NONE) null else ColorFilter.colorMatrix(ColorMatrix(StudioColor.androidColorMatrix(f))),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AutoTubeColors.SurfaceHighest)
                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
                    )
                    Text(f.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (clip.filter != FilterPreset.NONE) {
            SliderRow(title = "Strength", value = clip.filterStrength, valueRange = 0f..1f, valueLabel = ::pct, onCommit = { onChange(clip.filter, it) })
        }
        TextButton(onClick = onApplyAll) { Text("Apply to all clips") }
    }
}

@Composable
fun AdjustPanel(clip: StudioClip, onChange: (Adjustments) -> Unit, onApplyAll: () -> Unit, onDone: () -> Unit) {
    val a = clip.adjust
    val label: (Float) -> String = { v -> "${(v * 100).roundToInt()}" }
    PanelFrame("Adjust", onDone) {
        SliderRow("Brightness", a.brightness, -1f..1f, label, { onChange(a.copy(brightness = it)) })
        SliderRow("Contrast", a.contrast, -1f..1f, label, { onChange(a.copy(contrast = it)) })
        SliderRow("Saturation", a.saturation, -1f..1f, label, { onChange(a.copy(saturation = it)) })
        SliderRow("Warmth", a.warmth, -1f..1f, label, { onChange(a.copy(warmth = it)) })
        SliderRow("Tint", a.tint, -1f..1f, label, { onChange(a.copy(tint = it)) })
        Row {
            TextButton(onClick = { onChange(Adjustments()) }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
            TextButton(onClick = onApplyAll) { Text("Apply to all clips") }
        }
    }
}

@Composable
fun TransformPanel(clip: StudioClip, projectFit: FitMode, onChange: (StudioClip) -> Unit, onDone: () -> Unit) {
    PanelFrame("Transform", onDone) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onChange(clip.copy(rotation = (clip.rotation + 90) % 360)) }) {
                Icon(Icons.Rounded.RotateRight, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Rotate")
            }
            FilledTonalButton(onClick = { onChange(clip.copy(flipH = !clip.flipH)) }) {
                Icon(Icons.Rounded.Flip, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Mirror")
            }
            FilledTonalButton(onClick = { onChange(clip.copy(flipV = !clip.flipV)) }) { Text("Flip") }
        }
        FieldLabel("Fit in canvas")
        val options = listOf<FitMode?>(null, FitMode.FIT, FitMode.FILL)
        ChoiceChips(options, clip.fit, { it?.label ?: "Project (${projectFit.label})" }, { onChange(clip.copy(fit = it)) })
        SliderRow(
            title = "Zoom",
            value = clip.zoom,
            valueRange = 1f..4f,
            valueLabel = ::pct,
            onCommit = { onChange(clip.copy(zoom = (it * 20).roundToInt() / 20f)) },
        )
        TextButton(onClick = { onChange(clip.copy(rotation = 0, flipH = false, flipV = false, zoom = 1f, fit = null)) }) { Text("Reset") }
    }
}

@Composable
fun TransitionPanel(clip: StudioClip, isLast: Boolean, onChange: (ClipTransition, Long) -> Unit, onApplyAll: () -> Unit, onDone: () -> Unit) {
    PanelFrame("Transition to next clip", onDone) {
        if (isLast) {
            Text("The last clip has no next clip. Select another clip or tap a transition button between clips.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@PanelFrame
        }
        ChoiceChips(ClipTransition.entries, clip.transition, { it.label }, { onChange(it, clip.transitionMs) })
        if (clip.transition != ClipTransition.NONE) {
            SliderRow(
                title = "Duration",
                value = clip.transitionMs / 1000f,
                valueRange = 0.2f..1.5f,
                valueLabel = { String.format(Locale.US, "%.1f s", it) },
                onCommit = { onChange(clip.transition, (it * 10).roundToInt() * 100L) },
            )
        }
        Text("Transitions render on export; the live preview shows cuts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onApplyAll) { Text("Apply to all clips") }
    }
}

@Composable
fun CanvasPanel(project: StudioProject, onRatio: (CanvasRatio) -> Unit, onFit: (FitMode) -> Unit, onDone: () -> Unit) {
    PanelFrame("Canvas", onDone) {
        FieldLabel("Aspect ratio")
        ChoiceChips(CanvasRatio.entries, project.ratio, { ratioLabel(it) }, onRatio)
        FieldLabel("Clips")
        ChoiceChips(FitMode.entries, project.fit, { if (it == FitMode.FIT) "Fit (black bars)" else "Fill (crop)" }, onFit)
    }
}

private fun ratioLabel(r: CanvasRatio): String = when (r) {
    CanvasRatio.R9_16 -> "9:16 Shorts/Reels"
    CanvasRatio.R16_9 -> "16:9 YouTube"
    CanvasRatio.R1_1 -> "1:1 Square"
    CanvasRatio.R4_5 -> "4:5 Post"
    else -> r.label
}

// ---------------------------------------------------------------------------------------- text panels

private val TextColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD54F, 0xFFFF3D57, 0xFFFF7A45, 0xFF4ADE80,
    0xFF26C6B0, 0xFF5B9DFF, 0xFFB388FF, 0xFFFF80AB, 0xFF8C8F9C, 0xFF6D4C41,
)

@Composable
fun TextStylePanel(layer: TextLayer, onChange: (TextLayer) -> Unit, onDone: () -> Unit) {
    PanelFrame(if (layer.sticker) "Sticker" else "Text style", onDone) {
        SliderRow(
            title = "Size",
            value = layer.size,
            valueRange = 0.02f..0.25f,
            valueLabel = { "${(it * 1000).roundToInt() / 10f}" },
            onCommit = { onChange(layer.copy(size = it)) },
        )
        if (!layer.sticker) {
            FieldLabel("Font")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFont.entries.forEach { f ->
                    val selected = layer.font == f
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else AutoTubeColors.SurfaceHighest)
                            .clickable { onChange(layer.copy(font = f)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(f.label, fontFamily = composeFamily(f), color = Color.White, fontWeight = if (f == TextFont.BLACK) FontWeight.Black else null)
                    }
                }
            }
            FieldLabel("Colour")
            Swatches(layer.color) { onChange(layer.copy(color = it)) }
            FieldLabel("Style")
            ChoiceChips(TextLook.entries, layer.look, { it.label }, { onChange(layer.copy(look = it)) })
            if (layer.look != TextLook.PLAIN) {
                FieldLabel(if (layer.look == TextLook.BOX) "Label colour" else "Outline / glow colour")
                Swatches(layer.accent) { onChange(layer.copy(accent = it)) }
            }
            SwitchRow(title = "Bold", checked = layer.bold, onCheckedChange = { onChange(layer.copy(bold = it)) })
        }
        Row {
            TextButton(onClick = { onChange(layer.copy(x = 0.5f, y = 0.5f)) }) { Text("Centre") }
            TextButton(onClick = { onChange(layer.copy(x = 0.5f, y = 0.8f)) }) { Text("Bottom") }
            TextButton(onClick = { onChange(layer.copy(x = 0.5f, y = 0.2f)) }) { Text("Top") }
        }
    }
}

private fun composeFamily(f: TextFont): FontFamily = when (f) {
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CURSIVE, TextFont.CASUAL -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

@Composable
private fun Swatches(selected: Long, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextColors.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else AutoTubeColors.Outline, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
fun TextAnimPanel(layer: TextLayer, onChange: (TextAnim) -> Unit, onDone: () -> Unit) {
    PanelFrame("Animation", onDone) {
        ChoiceChips(TextAnim.entries, layer.anim, { it.label }, onChange)
        Spacer(Modifier.height(8.dp))
        Text(
            "In and out animations play at the start and end of the text. Drag the text bar's handles on the timeline to set when it shows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val Stickers = listOf(
    "🔥", "😂", "😍", "🤯", "😱", "👍", "👏", "🙌", "💯", "❤️", "💔", "✨",
    "⭐", "🎉", "🎬", "📌", "👉", "👈", "👆", "👇", "✅", "❌", "⚠️", "❓",
    "💡", "🚀", "💰", "📈", "📉", "🎵", "🔊", "😎", "🤔", "😭", "🥳", "😴",
    "🙏", "💪", "👀", "🏆", "🎯", "⏰", "📍", "🌍", "☀️", "🌙", "⚡", "🍿",
)

@Composable
fun StickersPanel(onPick: (String) -> Unit, onDone: () -> Unit) {
    PanelFrame("Stickers", onDone) {
        LazyVerticalGrid(GridCells.Adaptive(48.dp), modifier = Modifier.height(220.dp)) {
            items(Stickers) { s ->
                Box(Modifier.size(48.dp).clickable { onPick(s) }, contentAlignment = Alignment.Center) {
                    Text(s, fontSize = 28.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- audio panels

@Composable
fun AudioFadePanel(layer: AudioLayer, onChange: (AudioLayer) -> Unit, onDone: () -> Unit) {
    val maxS = (layer.durationMs / 2000f).coerceIn(0.1f, 10f)
    PanelFrame("Fade", onDone) {
        SliderRow("Fade in", layer.fadeInMs / 1000f, 0f..maxS, { String.format(Locale.US, "%.1f s", it) }, { onChange(layer.copy(fadeInMs = (it * 1000).toLong())) })
        SliderRow("Fade out", layer.fadeOutMs / 1000f, 0f..maxS, { String.format(Locale.US, "%.1f s", it) }, { onChange(layer.copy(fadeOutMs = (it * 1000).toLong())) })
    }
}

@Composable
fun RecordPanel(recording: Boolean, elapsedMs: Long, onStart: () -> Unit, onStop: () -> Unit, onDone: () -> Unit) {
    PanelFrame("Voiceover", onDone) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (recording) "Recording ${Format.clock(elapsedMs)}" else "Records from the playhead while the video plays (muted). Use headphones for best results.",
                textAlign = TextAlign.Center,
                color = if (recording) AutoTubeColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AutoTubeColors.Red)
                    .clickable { if (recording) onStop() else onStart() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                    contentDescription = if (recording) "Stop" else "Record",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
