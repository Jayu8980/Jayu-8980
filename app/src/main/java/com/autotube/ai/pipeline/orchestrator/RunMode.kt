package com.autotube.ai.pipeline.orchestrator

import com.autotube.ai.core.model.PipelineError

/** What a pipeline run should do. */
sealed class RunMode {
    /** Start or resume the whole pipeline; steps whose artifacts already exist are skipped. */
    data object Full : RunMode()

    /** Continue after the user picked a topic in review mode. */
    data object ResumeAfterTopic : RunMode()

    /** Re-create one scene (narration, voice, visuals) and re-render. */
    data class RegenerateScene(val sceneNumber: Int) : RunMode()

    /** Re-mix and re-render with the current edit decision list. */
    data object Rerender : RunMode()
}

/** How a pipeline run ended. */
sealed class RunOutcome {
    /** Nothing left to do (for example the video is already uploaded). */
    data object Completed : RunOutcome()

    /** Review mode: the user must choose one of the researched topics. */
    data object AwaitingTopic : RunOutcome()

    /** The video is exported and waiting for the user's review. */
    data object AwaitingReview : RunOutcome()

    /** The video is exported and an upload was scheduled. */
    data object UploadQueued : RunOutcome()

    /** The project had been cancelled before this run could start. */
    data object Cancelled : RunOutcome()

    data class Failed(val error: PipelineError) : RunOutcome()
}
