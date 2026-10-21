package com.autotube.ai.di

import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.data.remote.aiimage.OpenAiImageClient
import com.autotube.ai.data.remote.aiimage.ReplicateImageClient
import com.autotube.ai.data.remote.aivideo.CustomTextToVideoClient
import com.autotube.ai.data.remote.aivideo.ReplicateTextToVideoClient
import com.autotube.ai.data.remote.download.DownloaderImpl
import com.autotube.ai.data.remote.llm.LlmProviderImpl
import com.autotube.ai.data.remote.stock.PexelsClient
import com.autotube.ai.data.remote.stock.PixabayClient
import com.autotube.ai.data.remote.stt.WhisperSttEngine
import com.autotube.ai.data.remote.tts.ElevenLabsTtsEngine
import com.autotube.ai.data.remote.tts.OpenAiTtsEngine
import com.autotube.ai.data.remote.youtube.YouTubeDataClientImpl
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.ImageGenClient
import com.autotube.ai.domain.ImageGenProviderKey
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.SpeechToTextEngine
import com.autotube.ai.domain.StockMediaClient
import com.autotube.ai.domain.TextToVideoClient
import com.autotube.ai.domain.TextToVideoProviderKey
import com.autotube.ai.domain.TtsEngine
import com.autotube.ai.domain.TtsProviderKey
import com.autotube.ai.domain.YouTubeDataClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * Binds the remote API implementations (LLMs, cloud TTS/STT, stock media, AI video/images,
 * YouTube Data API, downloads) to their domain interfaces.
 *
 * Multibinding contributions:
 * - `Map<TtsProviderType, TtsEngine>`: OPENAI, ELEVENLABS (ON_DEVICE comes from the voice module);
 * - `Map<TextToVideoProviderType, TextToVideoClient>`: REPLICATE, CUSTOM;
 * - `Map<ImageGenProviderType, ImageGenClient>`: OPENAI, REPLICATE;
 * - `Set<StockMediaClient>`: Pexels, Pixabay.
 *
 * Every implementation is a @Singleton, so the bindings themselves are unscoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteBindingsModule {

    @Binds
    abstract fun bindLlmProvider(impl: LlmProviderImpl): LlmProvider

    @Binds
    abstract fun bindSpeechToTextEngine(impl: WhisperSttEngine): SpeechToTextEngine

    @Binds
    abstract fun bindYouTubeDataClient(impl: YouTubeDataClientImpl): YouTubeDataClient

    @Binds
    abstract fun bindDownloader(impl: DownloaderImpl): Downloader

    // ------------------------------------------------------------ TTS engines

    @Binds
    @IntoMap
    @TtsProviderKey(TtsProviderType.OPENAI)
    abstract fun bindOpenAiTts(impl: OpenAiTtsEngine): TtsEngine

    @Binds
    @IntoMap
    @TtsProviderKey(TtsProviderType.ELEVENLABS)
    abstract fun bindElevenLabsTts(impl: ElevenLabsTtsEngine): TtsEngine

    // ------------------------------------------------------------ Text-to-video

    @Binds
    @IntoMap
    @TextToVideoProviderKey(TextToVideoProviderType.REPLICATE)
    abstract fun bindReplicateTextToVideo(impl: ReplicateTextToVideoClient): TextToVideoClient

    @Binds
    @IntoMap
    @TextToVideoProviderKey(TextToVideoProviderType.CUSTOM)
    abstract fun bindCustomTextToVideo(impl: CustomTextToVideoClient): TextToVideoClient

    // ------------------------------------------------------------ Image generation

    @Binds
    @IntoMap
    @ImageGenProviderKey(ImageGenProviderType.OPENAI)
    abstract fun bindOpenAiImage(impl: OpenAiImageClient): ImageGenClient

    @Binds
    @IntoMap
    @ImageGenProviderKey(ImageGenProviderType.REPLICATE)
    abstract fun bindReplicateImage(impl: ReplicateImageClient): ImageGenClient

    // ------------------------------------------------------------ Stock media

    @Binds
    @IntoSet
    abstract fun bindPexels(impl: PexelsClient): StockMediaClient

    @Binds
    @IntoSet
    abstract fun bindPixabay(impl: PixabayClient): StockMediaClient
}
