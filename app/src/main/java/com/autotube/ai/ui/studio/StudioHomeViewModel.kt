package com.autotube.ai.ui.studio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.studio.CanvasRatio
import com.autotube.ai.studio.StudioEdits
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.StudioRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

sealed interface StudioHomeEvent {
    data class OpenEditor(val id: String) : StudioHomeEvent
    data class Message(val text: String) : StudioHomeEvent
}

@HiltViewModel
class StudioHomeViewModel @Inject constructor(
    private val repository: StudioRepository,
) : ViewModel() {

    val projects: StateFlow<List<StudioProject>> = repository.projects

    /** Import progress 0..1 while a new project is being created, else null. */
    private val _importing = MutableStateFlow<Float?>(null)
    val importing: StateFlow<Float?> = _importing.asStateFlow()

    private val _events = MutableSharedFlow<StudioHomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<StudioHomeEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.refresh() }
    }

    /** New project from picked media; the canvas ratio follows the first item. */
    fun createFrom(uris: List<Uri>) {
        if (uris.isEmpty() || _importing.value != null) return
        viewModelScope.launch {
            _importing.value = 0f
            val project = repository.create(defaultName())
            val clips = repository.importVisuals(project.id, uris) { _importing.value = it }
            _importing.value = null
            if (clips.isEmpty()) {
                repository.delete(project.id)
                _events.tryEmit(StudioHomeEvent.Message("Those files could not be read."))
                return@launch
            }
            val first = clips.first()
            val ratio = if (first.width > 0 && first.height > 0) {
                val aspect = first.width.toFloat() / first.height
                CanvasRatio.entries.minBy { kotlin.math.abs(it.aspect - aspect) }
            } else {
                CanvasRatio.R9_16
            }
            repository.save(StudioEdits.insertClips(project.copy(ratio = ratio), clips))
            _events.tryEmit(StudioHomeEvent.OpenEditor(project.id))
        }
    }

    /** Empty project (add media later from the editor). */
    fun createEmpty(ratio: CanvasRatio) {
        viewModelScope.launch {
            val project = repository.create(defaultName(), ratio)
            _events.tryEmit(StudioHomeEvent.OpenEditor(project.id))
        }
    }

    /** Opens an AutoTube-rendered video in the editor as a new project. */
    fun importFile(path: String, name: String) {
        viewModelScope.launch {
            _importing.value = 0f
            val project = repository.create(name.ifBlank { defaultName() })
            val clips = repository.importVisuals(project.id, listOf(Uri.fromFile(java.io.File(path))))
            _importing.value = null
            if (clips.isEmpty()) {
                repository.delete(project.id)
                _events.tryEmit(StudioHomeEvent.Message("That video could not be opened."))
                return@launch
            }
            val first = clips.first()
            val ratio = if (first.height > first.width) CanvasRatio.R9_16 else CanvasRatio.R16_9
            repository.save(StudioEdits.insertClips(project.copy(ratio = ratio), clips))
            _events.tryEmit(StudioHomeEvent.OpenEditor(project.id))
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            if (repository.duplicate(id) == null) _events.tryEmit(StudioHomeEvent.Message("Could not duplicate that project."))
        }
    }

    private fun defaultName(): String =
        "Video " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()))
}
