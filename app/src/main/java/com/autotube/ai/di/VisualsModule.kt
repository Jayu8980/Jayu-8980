package com.autotube.ai.di

import com.autotube.ai.domain.EditorBrain
import com.autotube.ai.domain.VisualSourcer
import com.autotube.ai.pipeline.editor.EditorBrainImpl
import com.autotube.ai.pipeline.visuals.VisualSourcerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the visuals sourcer (stock / AI / gallery / on-device art with vision check) and the AI
 * editor brain. Both implementations are @Singleton, so the bindings are unscoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisualsModule {

    @Binds
    abstract fun bindVisualSourcer(impl: VisualSourcerImpl): VisualSourcer

    @Binds
    abstract fun bindEditorBrain(impl: EditorBrainImpl): EditorBrain
}
