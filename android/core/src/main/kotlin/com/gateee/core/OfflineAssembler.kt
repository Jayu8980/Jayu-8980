package com.gateee.core

import kotlin.random.Random

/**
 * Builds a test from questions already on the phone (bundled bank, downloaded AI papers,
 * PYQs with text). Used when there is no network, and for the weak-area / daily offline modes.
 */
object OfflineAssembler {
    data class Spec(
        val subjects: Set<String> = emptySet(),
        val topics: Set<String> = emptySet(),
        val count: Int = 15,
        val difficulty: String? = null,
        val types: Set<QType> = QType.entries.toSet(),
        val preferTopics: List<String> = emptyList(),
        val exclude: Set<String> = emptySet(),
        val seed: Long = System.currentTimeMillis(),
    )

    fun assemble(bank: List<Question>, spec: Spec): List<Question> {
        val rnd = Random(spec.seed)
        val pool = bank.filter { q ->
            q.id !in spec.exclude &&
                (spec.subjects.isEmpty() || q.subjectId in spec.subjects) &&
                (spec.topics.isEmpty() || q.topicId in spec.topics) &&
                (spec.difficulty == null || spec.difficulty == "Mixed" || q.difficulty == spec.difficulty) &&
                q.type in spec.types
        }.shuffled(rnd)
        // weak / preferred topics first, then spread across topics round-robin for breadth
        val preferred = pool.filter { it.topicId in spec.preferTopics }
        val rest = pool.filter { it.topicId !in spec.preferTopics }
        val byTopic = rest.groupBy { it.topicId }.values.map { it.toMutableList() }.toMutableList()
        val picked = preferred.take(spec.count).toMutableList()
        while (picked.size < spec.count && byTopic.any { it.isNotEmpty() }) {
            for (list in byTopic) {
                if (picked.size >= spec.count) break
                if (list.isNotEmpty()) picked += list.removeAt(0)
            }
        }
        // GA first, then technical, as in the real paper
        return picked.sortedBy { if (it.section == "GA") 0 else 1 }.mapIndexed { i, q -> q.copy(qno = i + 1) }
    }

    /** Suggested duration: ~1.5 min per 1-mark and ~3 min per 2-mark question. */
    fun durationMin(questions: List<Question>): Int =
        maxOf(10, questions.map { if (it.marks == 1) 90 else 180 }.sum() / 60)
}
