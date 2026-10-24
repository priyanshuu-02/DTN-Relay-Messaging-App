package com.dtn.mesh.receiver

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live view of the phone radios the transports depend on: Bluetooth (BLE), Wi-Fi (hub + Wi-Fi
 * Direct), location services (required for Wi-Fi Direct / BLE scans on older Android), and
 * whether the default network can reach the internet (MQTT relay).
 *
 * The receiver never trusts intent extras — every broadcast just triggers a re-read of the real
 * system state — so it is registered exported: a spoofed broadcast can only cause a harmless
 * refresh, and system broadcasts are guaranteed to arrive on every Android version.
 */
@Singleton
class RadioStatusMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Radios(
        val bluetoothOn: Boolean,
        val wifiOn: Boolean,
        val locationOn: Boolean,
        /** The default network has validated internet (the hub's SoftAP is never the default network). */
        val internetAvailable: Boolean,
        /**
         * That network's handle while [internetAvailable] (0 otherwise): a new value means the phone
         * moved to another network (e.g. mobile data → Wi-Fi), which the relay reconnects over.
         */
        val internetNetworkId: Long = 0L,
    )

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _radios = MutableStateFlow(read())
    val radios: StateFlow<Radios> = _radios.asStateFlow()

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) = refresh()
                },
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "radio state receiver registration failed", it) }

        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        }.onFailure { Log.w(TAG, "default network callback registration failed", it) }
    }

    /** Re-read every radio now (also called when the app returns to the foreground). */
    fun refresh() {
        _radios.value = read()
    }

    private fun read(): Radios {
        val bt = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        val wifi = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
        }.getOrDefault(false)
        val location = runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.let { LocationManagerCompat.isLocationEnabled(it) } == true
        }.getOrDefault(false)
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        val internet = runCatching {
            connectivity.getNetworkCapabilities(active)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }.getOrDefault(false)
        val networkId = if (internet) runCatching { active?.networkHandle }.getOrNull() ?: 0L else 0L
        return Radios(bluetoothOn = bt, wifiOn = wifi, locationOn = location, internetAvailable = internet,
            internetNetworkId = networkId)
    }

    private companion object {
        const val TAG = "RadioStatus"
    }
}
