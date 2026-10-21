package com.autotube.ai.di

import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.domain.CaptionAligner
import com.autotube.ai.domain.LengthManager
import com.autotube.ai.domain.ScriptWriter
import com.autotube.ai.domain.SeoGenerator
import com.autotube.ai.domain.TimelineBuilder
import com.autotube.ai.domain.TopicResearcher
import com.autotube.ai.domain.TtsEngine
import com.autotube.ai.domain.TtsProviderKey
import com.autotube.ai.domain.VoiceSynthesizer
import com.autotube.ai.pipeline.captions.CaptionAlignerImpl
import com.autotube.ai.pipeline.length.LengthManagerImpl
import com.autotube.ai.pipeline.research.TopicResearcherImpl
import com.autotube.ai.pipeline.script.ScriptWriterImpl
import com.autotube.ai.pipeline.seo.SeoGeneratorImpl
import com.autotube.ai.pipeline.timeline.TimelineBuilderImpl
import com.autotube.ai.pipeline.voice.AndroidTtsEngine
import com.autotube.ai.pipeline.voice.VoiceSynthesizerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * Content pipeline steps: research, script, length, voice, captions, timeline and SEO, plus the
 * on-device (offline) TTS engine contributed to `Map<TtsProviderType, TtsEngine>`.
 * Implementations are @Singleton, so the bindings are unscoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContentModule {

    @Binds
    abstract fun bindTopicResearcher(impl: TopicResearcherImpl): TopicResearcher

    @Binds
    abstract fun bindScriptWriter(impl: ScriptWriterImpl): ScriptWriter

    @Binds
    abstract fun bindLengthManager(impl: LengthManagerImpl): LengthManager

    @Binds
    abstract fun bindVoiceSynthesizer(impl: VoiceSynthesizerImpl): VoiceSynthesizer

    @Binds
    abstract fun bindCaptionAligner(impl: CaptionAlignerImpl): CaptionAligner

    @Binds
    abstract fun bindTimelineBuilder(impl: TimelineBuilderImpl): TimelineBuilder

    @Binds
    abstract fun bindSeoGenerator(impl: SeoGeneratorImpl): SeoGenerator

    @Binds
    @IntoMap
    @TtsProviderKey(TtsProviderType.ON_DEVICE)
    abstract fun bindOnDeviceTts(impl: AndroidTtsEngine): TtsEngine
}
