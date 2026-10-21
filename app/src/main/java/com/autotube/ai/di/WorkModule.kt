package com.autotube.ai.di

import com.autotube.ai.domain.WorkScheduler
import com.autotube.ai.work.WorkSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Background work bindings (WorkManager scheduler). */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {

    @Binds
    abstract fun bindWorkScheduler(impl: WorkSchedulerImpl): WorkScheduler
}
