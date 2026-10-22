package com.dtn.mesh.ui

import android.Manifest
import android.os.Build

/**
 * Runtime permissions required by the DTN transport layer.
 * Called from [MainActivity] before any connect action.
 */
object PermissionHelper {

    /** All permissions needed for BLE + WiFi + Location. */
    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Location is only needed up to Android 12L (API 32): BLE scanning before Android 12 and
        // Wi-Fi Direct discovery before Android 13. From API 33 NEARBY_WIFI_DEVICES and the
        // BLUETOOTH_* permissions (both declared neverForLocation) cover it, and the manifest
        // caps location at maxSdkVersion 32 — so the app never asks for location there.
        // COARSE must accompany FINE or Android 12 (API 31-32) ignores a FINE-only request.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return perms.toTypedArray()
    }
}
