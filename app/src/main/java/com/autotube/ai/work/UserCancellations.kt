package com.autotube.ai.work

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which projects the user cancelled in this process, so a worker that is stopped can tell
 * a user cancel (project stays CANCELLED) from a system stop (WorkManager will run it again).
 */
@Singleton
class UserCancellations @Inject constructor() {
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    fun markCancelled(projectId: Long) {
        cancelled.add(projectId)
    }

    fun clear(projectId: Long) {
        cancelled.remove(projectId)
    }

    fun wasCancelled(projectId: Long): Boolean = projectId in cancelled
}
