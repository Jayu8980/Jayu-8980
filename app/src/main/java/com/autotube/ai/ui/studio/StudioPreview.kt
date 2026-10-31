package com.autotube.ai.ui.studio

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.StudioTextPainter
import com.autotube.ai.studio.TextLayer

/**
 * The canvas preview: the live CompositionPlayer picture letterboxed to the project ratio, with the
 * text layers drawn on top by the same painter the export uses. Tap a text to select it, double tap
 * to edit, drag to move and pinch to resize; tapping elsewhere toggles playback.
 */
@Composable
fun StudioPreview(
    player: Player,
    project: StudioProject,
    playheadMs: Long,
    selectedTextId: String?,
    onSelectText: (String?) -> Unit,
    onTogglePlay: () -> Unit,
    onEditText: (String) -> Unit,
    onTextGestureStart: () -> Unit,
    onTextTransform: (id: String, dx: Float, dy: Float, zoom: Float) -> Unit,
    onTextGestureEnd: () -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = remember { StudioTextPainter() }
    val texts by rememberUpdatedState(project.texts)
    val time by rememberUpdatedState(playheadMs)
    val selected by rememberUpdatedState(selectedTextId)

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF08090B)), contentAlignment = Alignment.Center) {
        val aspect = project.ratio.aspect
        val boxAspect = maxWidth / maxHeight
        val (w, h) = if (aspect > boxAspect) maxWidth to maxWidth / aspect else maxHeight * aspect to maxHeight
        Box(Modifier.size(w, h).background(Color.Black)) {
            if (project.clips.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onAddMedia() } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text("Tap to add videos or photos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(AndroidColor.BLACK)
                            setKeepContentOnPlayerReset(true)
                            this.player = player
                        }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            fun visibleAt(t: Long): List<TextLayer> = texts.filter { t >= it.startMs && t < it.endMs }

            fun hit(pos: Offset, wPx: Int, hPx: Int): TextLayer? =
                visibleAt(time).asReversed().firstOrNull { painter.bounds(it, wPx, hPx).contains(pos.x, pos.y) }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { pos ->
                                val target = hit(pos, size.width, size.height)
                                when {
                                    target != null -> onSelectText(target.id)
                                    selected != null -> onSelectText(null)
                                    else -> onTogglePlay()
                                }
                            },
                            onDoubleTap = { pos -> hit(pos, size.width, size.height)?.let { onEditText(it.id) } },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val target = hit(down.position, size.width, size.height)
                                ?: texts.firstOrNull { it.id == selected && time >= it.startMs && time < it.endMs }
                                ?: return@awaitEachGesture
                            var started = false
                            do {
                                val event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val moved = event.changes.any { it.positionChanged() }
                                if (moved && (pan != Offset.Zero || zoom != 1f)) {
                                    if (!started) {
                                        started = true
                                        onSelectText(target.id)
                                        onTextGestureStart()
                                    }
                                    onTextTransform(target.id, pan.x / size.width, pan.y / size.height, zoom)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (started) onTextGestureEnd()
                        }
                    },
            ) {
                val wPx = size.width.toInt()
                val hPx = size.height.toInt()
                drawIntoCanvas { canvas -> painter.drawAll(canvas.nativeCanvas, texts, wPx, hPx, playheadMs) }
                val sel = texts.firstOrNull { it.id == selectedTextId && playheadMs >= it.startMs && playheadMs < it.endMs }
                if (sel != null) {
                    val r = painter.bounds(sel, wPx, hPx)
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width(), r.height()),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                    )
                }
            }
        }
    }
}
