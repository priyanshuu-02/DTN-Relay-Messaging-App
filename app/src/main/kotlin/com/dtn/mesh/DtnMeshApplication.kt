package com.dtn.mesh

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.PowerManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dtn.mesh.model.LocalNodeIdentity
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    @Inject lateinit var diagnostics: DiagnosticsLog

    /** Lazy: the identity (Keystore unwrap) is only touched off the main thread, below. */
    @Inject lateinit var identity: dagger.Lazy<LocalNodeIdentity>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { logAppStart() }
    }

    /** System line: version, node id, Android version, and the power limits that slow a relay down. */
    private fun logAppStart() {
        val node = runCatching { identity.get().nodeId.value.takeLast(8) }.getOrDefault("?")
        diagnostics.i(DiagSource.SYSTEM, "App start · DTN Mesh Relay ${BuildConfig.VERSION_NAME} " +
            "(build ${BuildConfig.VERSION_CODE}) · node $node · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val power = getSystemService(PowerManager::class.java)
        val saver = power?.isPowerSaveMode == true
        val exempt = power?.isIgnoringBatteryOptimizations(packageName) == true
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        val line = "Power: battery saver ${if (saver) "on" else "off"} · battery optimisation " +
            "${if (exempt) "off (exempt)" else "on"} · background ${if (restricted) "RESTRICTED" else "allowed"}"
        if (saver || restricted) diagnostics.w(DiagSource.SYSTEM, line) else diagnostics.i(DiagSource.SYSTEM, line)
    }
}
