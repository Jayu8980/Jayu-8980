package com.gateee.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateee.app.AppContainer
import com.gateee.app.data.AttemptEntity
import com.gateee.app.data.LoadedTest
import com.gateee.app.data.QState
import com.gateee.core.QStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Exam state for one attempt. Mirrors the GATE interface: a response only counts once it is
 * saved ("Save & Next" / "Mark for Review & Next"); "Answered & Marked for Review" is evaluated.
 */
class TestViewModel(private val container: AppContainer, private val testId: String) : ViewModel() {
    var loaded by mutableStateOf<LoadedTest?>(null)
        private set
    var current by mutableIntStateOf(0)
        private set
    var remainingSec by mutableIntStateOf(0)
        private set
    var submittedAttemptId by mutableStateOf<Long?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set

    val states = mutableStateMapOf<String, QState>()
    /** unsaved selections, like the real exam: lost if you jump away without saving */
    val drafts = mutableStateMapOf<String, String?>()

    private var attempt: AttemptEntity? = null
    private var timer: Job? = null

    init {
        viewModelScope.launch {
            val l = container.repo.loadTest(testId) ?: return@launch
            val (a, s) = container.repo.startOrResume(testId, l.questions)
            attempt = a
            states.putAll(s)
            l.questions.forEach { q -> drafts[q.id] = s[q.id]?.answer }
            loaded = l
            val elapsed = ((System.currentTimeMillis() - a.startedAt) / 1000).toInt()
            remainingSec = l.test.durationMin * 60 - elapsed
            go(a.currentIndex.coerceIn(0, (l.questions.size - 1).coerceAtLeast(0)))
            if (remainingSec <= 0) submit(auto = true) else startTimer()
        }
    }

    private fun startTimer() {
        timer = viewModelScope.launch {
            var tick = 0
            while (isActive && remainingSec > 0) {
                delay(1000)
                remainingSec--
                currentQuestionId()?.let { id -> states[id] = states.getValue(id).let { it.copy(timeSpentSec = it.timeSpentSec + 1) } }
                if (++tick % 15 == 0) persist()
            }
            if (remainingSec <= 0) submit(auto = true)
        }
    }

    private fun currentQuestionId() = loaded?.questions?.getOrNull(current)?.id
    private fun elapsedSec() = (loaded?.test?.durationMin ?: 0) * 60 - remainingSec

    fun go(index: Int) {
        val qs = loaded?.questions ?: return
        if (index !in qs.indices) return
        // discard an unsaved selection on the question being left
        currentQuestionId()?.let { drafts[it] = states[it]?.answer }
        current = index
        val id = qs[index].id
        if (states[id]?.status == QStatus.NOT_VISITED) states[id] = states.getValue(id).copy(status = QStatus.NOT_ANSWERED)
    }

    fun setDraft(value: String?) {
        currentQuestionId()?.let { drafts[it] = value }
    }

    fun saveAndNext() = commit(mark = false)
    fun markForReviewAndNext() = commit(mark = true)

    private fun commit(mark: Boolean) {
        val id = currentQuestionId() ?: return
        val d = drafts[id]?.takeIf { it.isNotBlank() }
        val status = when {
            d != null && mark -> QStatus.ANSWERED_MARKED
            d != null -> QStatus.ANSWERED
            mark -> QStatus.MARKED
            else -> QStatus.NOT_ANSWERED
        }
        states[id] = states.getValue(id).copy(answer = d, status = status, answeredAtSec = if (d != null) elapsedSec() else null)
        val next = current + 1
        if (next < (loaded?.questions?.size ?: 0)) go(next) else persist()
    }

    fun clearResponse() {
        val id = currentQuestionId() ?: return
        drafts[id] = null
        states[id] = states.getValue(id).copy(answer = null, status = QStatus.NOT_ANSWERED, answeredAtSec = null)
    }

    fun counts(): Map<QStatus, Int> = QStatus.entries.associateWith { s -> states.values.count { it.status == s } }

    fun persist() {
        val a = attempt ?: return
        viewModelScope.launch { container.repo.saveProgress(a, states.toMap(), current) }
    }

    fun submit(auto: Boolean = false) {
        val l = loaded ?: return
        val a = attempt ?: return
        if (submitting || submittedAttemptId != null) return
        submitting = true
        timer?.cancel()
        viewModelScope.launch {
            val id = container.repo.submit(a, l.test, l.questions, states.toMap(), elapsedSec().coerceAtMost(l.test.durationMin * 60), auto)
            submittedAttemptId = id
        }
    }

    override fun onCleared() {
        if (submittedAttemptId == null) {
            val a = attempt ?: return
            val snapshot = states.toMap()
            val idx = current
            container.scope.launch { container.repo.saveProgress(a, snapshot, idx) }
        }
    }
}
