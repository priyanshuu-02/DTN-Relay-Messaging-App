package com.dtn.mesh.scheduler

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.QLearningConfig
import com.dtn.mesh.queue.BufferConfig
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.routing.EpidemicStrategy
import com.dtn.mesh.routing.MaxPropStrategy
import com.dtn.mesh.routing.ProphetConfig
import com.dtn.mesh.routing.ProphetStrategy
import com.dtn.mesh.routing.QLearningStrategy
import com.dtn.mesh.routing.StrategySelector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing scheduler, routing, Q-engine, and queue singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object SchedulerModule {

    @Provides
    @Singleton
    fun provideSchedulerConfig(): SchedulerConfig = SchedulerConfig()

    @Provides
    @Singleton
    fun provideBufferConfig(): BufferConfig = BufferConfig()

    @Provides
    @Singleton
    fun provideQLearningConfig(): QLearningConfig = QLearningConfig()

    @Provides
    @Singleton
    fun provideDoubleQLearningEngine(config: QLearningConfig): DoubleQLearningEngine =
        DoubleQLearningEngine(config)

    @Provides
    @Singleton
    fun provideStrategySelector(): StrategySelector =
        StrategySelector(
            prophet = ProphetStrategy(),
            maxProp = MaxPropStrategy(),
            epidemic = EpidemicStrategy(),
            stableProphet = ProphetStrategy(ProphetConfig(stabilityAware = true)),
            qLearning = QLearningStrategy(),
            initialStrategy = StrategySelector.StrategyType.PROPHET,
        )

    // NOTE: AirtimeBudgetTracker is intentionally NOT provided here. It's a LoRa duty-cycle /
    // airtime rate limiter, but on the phone the LoRa link lives on the ESP32 hub (the phone
    // reaches it over WiFi TCP), so there is no phone-side LoRa airtime to gate. The class is
    // kept (with its unit tests) as a ready building block for a future direct-LoRa phone
    // transport; wiring an unused singleton into the DI graph was just dead weight.
}
