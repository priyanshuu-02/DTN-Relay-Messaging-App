package com.dtn.mesh

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for DTN Mesh Relay Layer.
 *
 * `@HiltAndroidApp` triggers Hilt code generation and serves as the application-level DI container.
 *
 * Implements [Configuration.Provider] so WorkManager builds its own configuration from a
 * [HiltWorkerFactory]. This is REQUIRED because [com.dtn.mesh.scheduler.ForwardingWorker] is a
 * `@HiltWorker` with an `@AssistedInject` constructor (it needs the queue manager, strategy
 * selector, DAOs, orchestrator, and lifecycle log injected). Without a Hilt-aware worker factory,
 * WorkManager's default factory would reflect on a `(Context, WorkerParameters)` constructor that
 * doesn't exist and throw `NoSuchMethodException` when the periodic housekeeping cycle fires in the
 * background. The matching manifest change removes the default `WorkManagerInitializer` so this
 * on-demand configuration is the one that's used.
 */
@HiltAndroidApp
class DtnMeshApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
