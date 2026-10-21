package com.autotube.ai.di

import android.os.Build
import com.autotube.ai.BuildConfig
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.data.remote.http.HttpClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * Networking singletons: the shared [Json] ([AppJson]), the app-wide [OkHttpClient] and a fresh
 * [Retrofit.Builder] (kotlinx-serialization converter, shared client) per injection site; callers
 * add their own base URL.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = AppJson

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = HttpClientFactory.create(
        debug = BuildConfig.DEBUG,
        userAgent = "AutoTubeAI/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
    )

    @Provides
    fun provideRetrofitBuilder(client: OkHttpClient, json: Json): Retrofit.Builder =
        Retrofit.Builder()
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
}
