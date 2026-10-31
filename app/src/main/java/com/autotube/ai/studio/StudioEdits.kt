package com.autotube.ai.studio

/**
 * Pure editing operations on a [StudioProject]. Each returns a new project (or the same instance when
 * the edit does not apply) so the editor can keep snapshots for undo/redo.
 */
object StudioEdits {

    /** Shortest clip/layer an edit may leave behind. */
    const val MIN_MS = 100L
    const val DEFAULT_IMAGE_MS = 3_000L
    const val DEFAULT_TEXT_MS = 3_000L
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f

    // ------------------------------------------------------------------ clips

    fun insertClips(p: StudioProject, clips: List<StudioClip>, atIndex: Int = p.clips.size): StudioProject {
        if (clips.isEmpty()) return p
        val list = p.clips.toMutableList()
        list.addAll(atIndex.coerceIn(0, list.size), clips)
        return p.copy(clips = list)
    }

    /** Index after the clip under [timeMs] (where new media is inserted in CapCut). */
    fun insertIndexAt(p: StudioProject, timeMs: Long): Int {
        if (p.clips.isEmpty()) return 0
        val i = p.clipIndexAt(timeMs)
        val start = p.clipStarts()[i]
        val clip = p.clips[i]
        return if (timeMs - start < clip.durationMs / 2) i else i + 1
    }

    fun updateClip(p: StudioProject, id: String, change: (StudioClip) -> StudioClip): StudioProject {
        val i = p.clips.indexOfFirst { it.id == id }
        if (i < 0) return p
        val updated = sanitize(change(p.clips[i]))
        if (updated == p.clips[i]) return p
        return p.copy(clips = p.clips.toMutableList().also { it[i] = updated })
    }

    /** Applies [change] to every clip (used by "apply to all"). */
    fun updateAllClips(p: StudioProject, change: (StudioClip) -> StudioClip): StudioProject =
        p.copy(clips = p.clips.map { sanitize(change(it)) })

    fun deleteClip(p: StudioProject, id: String): StudioProject = p.copy(clips = p.clips.filterNot { it.id == id })

    fun duplicateClip(p: StudioProject, id: String): Pair<StudioProject, String?> {
        val i = p.clips.indexOfFirst { it.id == id }
        if (i < 0) return p to null
        val copy = p.clips[i].copy(id = newId())
        return p.copy(clips = p.clips.toMutableList().also { it.add(i + 1, copy) }) to copy.id
    }

    /** Moves a clip [delta] positions left (negative) or right. */
    fun moveClip(p: StudioProject, id: String, delta: Int): StudioProject {
        val i = p.clips.indexOfFirst { it.id == id }
        if (i < 0) return p
        val target = (i + delta).coerceIn(0, p.clips.lastIndex)
        if (target == i) return p
        val list = p.clips.toMutableList()
        val clip = list.removeAt(i)
        list.add(target, clip)
        return p.copy(clips = list)
    }

    /**
     * Splits the clip under [timeMs]. The left part keeps the id; returns the id of the right part,
     * or null when the split point is too close to a clip edge.
     */
    fun splitClipAt(p: StudioProject, timeMs: Long): Pair<StudioProject, String?> {
        val i = p.clipIndexAt(timeMs)
        if (i < 0) return p to null
        val clip = p.clips[i]
        val local = timeMs - p.clipStarts()[i]
        if (local < MIN_MS || clip.durationMs - local < MIN_MS) return p to null
        val splitSource = clip.sourceTimeAt(local)
        val left = clip.copy(trimEndMs = splitSource, transition = ClipTransition.NONE)
        val right = clip.copy(id = newId(), trimStartMs = splitSource)
        val list = p.clips.toMutableList()
        list[i] = left
        list.add(i + 1, right)
        return p.copy(clips = list) to right.id
    }

    /** Sets the trim window, keeping at least [MIN_MS] and staying inside the source (videos). */
    fun trimClip(p: StudioProject, id: String, startMs: Long, endMs: Long): StudioProject =
        updateClip(p, id) { c ->
            if (c.isImage) {
                c.copy(trimStartMs = 0L, trimEndMs = (endMs - startMs).coerceAtLeast(MIN_MS))
            } else {
                val s = startMs.coerceIn(0L, c.sourceDurationMs - MIN_MS)
                val e = endMs.coerceIn(s + MIN_MS, c.sourceDurationMs)
                c.copy(trimStartMs = s, trimEndMs = e)
            }
        }

    /** Deletes everything of the clip under the playhead to the left (or right) of it. */
    fun trimToPlayhead(p: StudioProject, timeMs: Long, keepRight: Boolean): StudioProject {
        val i = p.clipIndexAt(timeMs)
        if (i < 0) return p
        val clip = p.clips[i]
        val local = timeMs - p.clipStarts()[i]
        if (local < MIN_MS || clip.durationMs - local < MIN_MS) return p
        val src = clip.sourceTimeAt(local)
        return if (keepRight) {
            if (clip.isImage) trimClip(p, clip.id, 0L, clip.trimmedMs - local) else trimClip(p, clip.id, src, clip.trimEndMs)
        } else {
            if (clip.isImage) trimClip(p, clip.id, 0L, local) else trimClip(p, clip.id, clip.trimStartMs, src)
        }
    }

    private fun sanitize(c: StudioClip): StudioClip {
        val speed = if (c.isImage) 1f else c.speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val rotation = ((c.rotation % 360) + 360) % 360 / 90 * 90
        return c.copy(
            speed = speed,
            volume = c.volume.coerceIn(0f, 2f),
            filterStrength = c.filterStrength.coerceIn(0f, 1f),
            rotation = rotation,
            zoom = c.zoom.coerceIn(1f, 4f),
            transitionMs = c.transitionMs.coerceIn(100L, 2_000L),
        )
    }

    // ------------------------------------------------------------------ text

    fun addText(p: StudioProject, layer: TextLayer): StudioProject = p.copy(texts = p.texts + sanitize(layer))

    fun updateText(p: StudioProject, id: String, change: (TextLayer) -> TextLayer): StudioProject =
        p.copy(texts = p.texts.map { if (it.id == id) sanitize(change(it)) else it })

    fun deleteText(p: StudioProject, id: String): StudioProject = p.copy(texts = p.texts.filterNot { it.id == id })

    fun duplicateText(p: StudioProject, id: String): Pair<StudioProject, String?> {
        val t = p.text(id) ?: return p to null
        val copy = t.copy(id = newId(), startMs = t.endMs, endMs = t.endMs + t.durationMs)
        return p.copy(texts = p.texts + copy) to copy.id
    }

    fun splitText(p: StudioProject, id: String, timeMs: Long): Pair<StudioProject, String?> {
        val t = p.text(id) ?: return p to null
        if (timeMs - t.startMs < MIN_MS || t.endMs - timeMs < MIN_MS) return p to null
        val right = t.copy(id = newId(), startMs = timeMs)
        return p.copy(texts = p.texts.map { if (it.id == id) it.copy(endMs = timeMs) else it } + right) to right.id
    }

    private fun sanitize(t: TextLayer): TextLayer {
        val start = t.startMs.coerceAtLeast(0L)
        return t.copy(
            startMs = start,
            endMs = t.endMs.coerceAtLeast(start + MIN_MS),
            x = t.x.coerceIn(0f, 1f),
            y = t.y.coerceIn(0f, 1f),
            size = t.size.coerceIn(0.015f, 0.3f),
        )
    }

    // ------------------------------------------------------------------ audio

    fun addAudio(p: StudioProject, layer: AudioLayer): StudioProject = p.copy(audio = p.audio + sanitize(layer))

    fun updateAudio(p: StudioProject, id: String, change: (AudioLayer) -> AudioLayer): StudioProject =
        p.copy(audio = p.audio.map { if (it.id == id) sanitize(change(it)) else it })

    fun deleteAudio(p: StudioProject, id: String): StudioProject = p.copy(audio = p.audio.filterNot { it.id == id })

    fun duplicateAudio(p: StudioProject, id: String): Pair<StudioProject, String?> {
        val a = p.audioLayer(id) ?: return p to null
        val copy = a.copy(id = newId(), startMs = a.endMs)
        return p.copy(audio = p.audio + copy) to copy.id
    }

    fun splitAudio(p: StudioProject, id: String, timeMs: Long): Pair<StudioProject, String?> {
        val a = p.audioLayer(id) ?: return p to null
        val local = timeMs - a.startMs
        if (local < MIN_MS || a.durationMs - local < MIN_MS) return p to null
        val left = a.copy(durationMs = local, fadeOutMs = 0L)
        val right = a.copy(id = newId(), startMs = timeMs, trimStartMs = a.trimStartMs + local, durationMs = a.durationMs - local, fadeInMs = 0L)
        return p.copy(audio = p.audio.map { if (it.id == id) left else it } + right) to right.id
    }

    /** Moves/trims an audio layer: [startMs] on the timeline, [trimStartMs] into the file, [durationMs]. */
    fun placeAudio(p: StudioProject, id: String, startMs: Long, trimStartMs: Long, durationMs: Long): StudioProject =
        updateAudio(p, id) { it.copy(startMs = startMs, trimStartMs = trimStartMs, durationMs = durationMs) }

    private fun sanitize(a: AudioLayer): AudioLayer {
        val trim = a.trimStartMs.coerceIn(0L, (a.sourceDurationMs - MIN_MS).coerceAtLeast(0L))
        val maxLen = (a.sourceDurationMs - trim).coerceAtLeast(MIN_MS)
        val len = a.durationMs.coerceIn(MIN_MS, maxLen)
        return a.copy(
            startMs = a.startMs.coerceAtLeast(0L),
            trimStartMs = trim,
            durationMs = len,
            volume = a.volume.coerceIn(0f, 2f),
            fadeInMs = a.fadeInMs.coerceIn(0L, len / 2),
            fadeOutMs = a.fadeOutMs.coerceIn(0L, len / 2),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Lane per audio layer so overlapping layers are drawn on separate rows. */
    fun audioLanes(layers: List<AudioLayer>): Map<String, Int> {
        val laneEnds = ArrayList<Long>()
        val out = HashMap<String, Int>()
        for (a in layers.sortedBy { it.startMs }) {
            var lane = laneEnds.indexOfFirst { it <= a.startMs }
            if (lane < 0) {
                laneEnds += a.endMs
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = a.endMs
            }
            out[a.id] = lane
        }
        return out
    }

    /** Same idea for text layers. */
    fun textLanes(layers: List<TextLayer>): Map<String, Int> {
        val laneEnds = ArrayList<Long>()
        val out = HashMap<String, Int>()
        for (t in layers.sortedBy { it.startMs }) {
            var lane = laneEnds.indexOfFirst { it <= t.startMs }
            if (lane < 0) {
                laneEnds += t.endMs
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = t.endMs
            }
            out[t.id] = lane
        }
        return out
    }
}
