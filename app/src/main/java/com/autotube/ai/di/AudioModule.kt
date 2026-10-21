package com.autotube.ai.di

import com.autotube.ai.domain.AudioEngine
import com.autotube.ai.domain.AudioIO
import com.autotube.ai.pipeline.audio.AudioEngineImpl
import com.autotube.ai.pipeline.audio.AudioIOImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Audio engine bindings. The implementations are @Singleton, so the bindings are unscoped. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    abstract fun bindAudioIO(impl: AudioIOImpl): AudioIO

    @Binds
    abstract fun bindAudioEngine(impl: AudioEngineImpl): AudioEngine
}
