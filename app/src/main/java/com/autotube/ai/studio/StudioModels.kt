package com.autotube.ai.studio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data model of the manual video editor ("Studio"). A project is one main video track of [StudioClip]s
 * laid end to end, a text track of [TextLayer]s and any number of [AudioLayer]s (music, voiceover,
 * extracted audio) placed freely on the timeline. Everything is plain immutable data so edits are
 * pure functions ([StudioEdits]) and undo/redo is a stack of snapshots.
 */
@Serializable
data class StudioProject(
    val id: String = newId(),
    val name: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val ratio: CanvasRatio = CanvasRatio.R9_16,
    /** How clips fill the canvas unless a clip overrides it. */
    val fit: FitMode = FitMode.FIT,
    val clips: List<StudioClip> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val audio: List<AudioLayer> = emptyList(),
    /** Output resolution (short side in px) of the last export. */
    val exportShortSide: Int = 1080,
    /** Path of the last exported MP4 inside app storage. */
    val lastExportPath: String? = null,
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    /** Timeline start of each clip, same order as [clips]. */
    fun clipStarts(): LongArray {
        val out = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c ->
            out[i] = t
            t += c.durationMs
        }
        return out
    }

    /** Index of the clip under [timeMs] (the last clip at the very end), or -1 when empty. */
    fun clipIndexAt(timeMs: Long): Int {
        if (clips.isEmpty()) return -1
        var t = 0L
        clips.forEachIndexed { i, c ->
            if (timeMs < t + c.durationMs) return i
            t += c.durationMs
        }
        return clips.lastIndex
    }

    fun clip(id: String): StudioClip? = clips.firstOrNull { it.id == id }
    fun text(id: String): TextLayer? = texts.firstOrNull { it.id == id }
    fun audioLayer(id: String): AudioLayer? = audio.firstOrNull { it.id == id }
}

@Serializable
enum class CanvasRatio(val w: Int, val h: Int, val label: String) {
    @SerialName("9:16") R9_16(9, 16, "9:16"),
    @SerialName("16:9") R16_9(16, 9, "16:9"),
    @SerialName("1:1") R1_1(1, 1, "1:1"),
    @SerialName("4:5") R4_5(4, 5, "4:5"),
    @SerialName("3:4") R3_4(3, 4, "3:4"),
    @SerialName("4:3") R4_3(4, 3, "4:3"),
    @SerialName("21:9") R21_9(21, 9, "21:9");

    val aspect: Float get() = w.toFloat() / h

    /** Even pixel size for a given short side (720, 1080, 1440...). */
    fun sizeFor(shortSide: Int): Pair<Int, Int> {
        val (w, h) = if (this.w <= this.h) {
            shortSide to shortSide * this.h / this.w
        } else {
            shortSide * this.w / this.h to shortSide
        }
        return (w and 1.inv()) to (h and 1.inv())
    }
}

@Serializable
enum class FitMode(val label: String) {
    /** Whole picture visible, black bars where the ratio differs. */
    @SerialName("fit") FIT("Fit"),
    /** Picture covers the canvas, edges cropped. */
    @SerialName("fill") FILL("Fill"),
}

@Serializable
enum class FilterPreset(val label: String) {
    @SerialName("none") NONE("Original"),
    @SerialName("vivid") VIVID("Vivid"),
    @SerialName("warm") WARM("Warm"),
    @SerialName("cool") COOL("Cool"),
    @SerialName("cinematic") CINEMATIC("Cinema"),
    @SerialName("vintage") VINTAGE("Vintage"),
    @SerialName("fade") FADE("Faded"),
    @SerialName("drama") DRAMA("Drama"),
    @SerialName("mono") MONO("B&W"),
    @SerialName("noir") NOIR("Noir"),
    @SerialName("sunset") SUNSET("Sunset"),
    @SerialName("forest") FOREST("Forest"),
}

/** Manual colour adjustments, each in -1..1 (0 = unchanged). */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
) {
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f && tint == 0f
}

@Serializable
enum class ClipTransition(val label: String) {
    @SerialName("none") NONE("None"),
    @SerialName("fade") FADE("Fade"),
    @SerialName("dip_black") DIP_BLACK("Dip to black"),
    @SerialName("zoom") ZOOM("Zoom"),
    @SerialName("whip") WHIP("Whip"),
}

@Serializable
data class StudioClip(
    val id: String = newId(),
    /** Copy of the media inside the project folder. */
    val path: String,
    val isImage: Boolean,
    val hasAudio: Boolean,
    /** Full length of the source (images: the default still duration). */
    val sourceDurationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
    val adjust: Adjustments = Adjustments(),
    /** Extra clockwise rotation: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Extra zoom applied after fitting (1 = none). */
    val zoom: Float = 1f,
    /** Null uses the project's [StudioProject.fit]. */
    val fit: FitMode? = null,
    /** Transition into the next clip. */
    val transition: ClipTransition = ClipTransition.NONE,
    val transitionMs: Long = 500L,
) {
    val trimmedMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    /** Length on the timeline after speed. */
    val durationMs: Long get() = if (isImage) trimmedMs else (trimmedMs / speed.toDouble()).toLong()

    /** Source time (ms) for a time inside this clip on the timeline. */
    fun sourceTimeAt(localMs: Long): Long =
        trimStartMs + if (isImage) localMs else (localMs * speed.toDouble()).toLong()
}

@Serializable
enum class TextLook(val label: String) {
    @SerialName("plain") PLAIN("Plain"),
    @SerialName("outline") OUTLINE("Outline"),
    @SerialName("shadow") SHADOW("Shadow"),
    @SerialName("box") BOX("Label"),
    @SerialName("neon") NEON("Neon"),
}

/** Android system font families available on every device. */
@Serializable
enum class TextFont(val label: String, val family: String) {
    @SerialName("sans") SANS("Sans", "sans-serif"),
    @SerialName("black") BLACK("Heavy", "sans-serif-black"),
    @SerialName("condensed") CONDENSED("Condensed", "sans-serif-condensed"),
    @SerialName("serif") SERIF("Serif", "serif"),
    @SerialName("mono") MONO("Mono", "monospace"),
    @SerialName("casual") CASUAL("Casual", "casual"),
    @SerialName("cursive") CURSIVE("Script", "cursive"),
}

@Serializable
enum class TextAnim(val label: String) {
    @SerialName("none") NONE("None"),
    @SerialName("fade") FADE("Fade"),
    @SerialName("pop") POP("Pop"),
    @SerialName("slide_up") SLIDE_UP("Slide up"),
    @SerialName("typewriter") TYPEWRITER("Typewriter"),
}

@Serializable
data class TextLayer(
    val id: String = newId(),
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Centre of the text as a fraction of the canvas. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Font size as a fraction of the canvas height. */
    val size: Float = 0.05f,
    val color: Long = 0xFFFFFFFF,
    val accent: Long = 0xFF000000,
    val look: TextLook = TextLook.OUTLINE,
    val bold: Boolean = true,
    val font: TextFont = TextFont.SANS,
    val anim: TextAnim = TextAnim.POP,
    /** Sticker layers are emoji: drawn without outline or box. */
    val sticker: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
enum class AudioKind(val label: String) {
    @SerialName("music") MUSIC("Music"),
    @SerialName("voice") VOICEOVER("Voiceover"),
    @SerialName("extracted") EXTRACTED("Extracted"),
    @SerialName("sfx") SFX("Sound"),
}

@Serializable
data class AudioLayer(
    val id: String = newId(),
    val path: String,
    val name: String,
    val kind: AudioKind = AudioKind.MUSIC,
    /** Position on the timeline. */
    val startMs: Long = 0L,
    /** Offset into the source file. */
    val trimStartMs: Long = 0L,
    /** Length on the timeline. */
    val durationMs: Long,
    val sourceDurationMs: Long,
    val volume: Float = 1f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    val endMs: Long get() = startMs + durationMs
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
