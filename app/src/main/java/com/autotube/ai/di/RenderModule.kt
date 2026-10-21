package com.autotube.ai.di

import com.autotube.ai.domain.QualityChecker
import com.autotube.ai.domain.ThumbnailMaker
import com.autotube.ai.domain.VideoRenderer
import com.autotube.ai.pipeline.qc.QualityCheckerImpl
import com.autotube.ai.pipeline.render.VideoRendererImpl
import com.autotube.ai.pipeline.thumbnail.ThumbnailMakerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** On-device render (Media3), quality check and thumbnail bindings. Implementations are @Singleton. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RenderModule {

    @Binds
    abstract fun bindVideoRenderer(impl: VideoRendererImpl): VideoRenderer

    @Binds
    abstract fun bindQualityChecker(impl: QualityCheckerImpl): QualityChecker

    @Binds
    abstract fun bindThumbnailMaker(impl: ThumbnailMakerImpl): ThumbnailMaker
}
