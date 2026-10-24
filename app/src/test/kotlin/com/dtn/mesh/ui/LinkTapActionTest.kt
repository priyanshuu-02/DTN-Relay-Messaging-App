package com.dtn.mesh.ui

import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The link tap rule shared by the Network tiles and the Connection links sheet. */
class LinkTapActionTest {

    private val allOn = RadioStatusMonitor.Radios(bluetoothOn = true, wifiOn = true, locationOn = true, internetAvailable = true)

    @Test
    fun runningOrStartingLinksShowDetails() {
        TransportKind.entries.forEach { kind ->
            assertEquals(LinkTapAction.SHOW_DETAILS, linkTapAction(kind, LinkHealth.ON, allOn, wifiDirectSupported = true))
            assertEquals(LinkTapAction.SHOW_DETAILS, linkTapAction(kind, LinkHealth.BUSY, allOn, wifiDirectSupported = true))
        }
    }

    @Test
    fun offOrFailedLinksToggle() {
        TransportKind.entries.forEach { kind ->
            assertEquals(LinkTapAction.TOGGLE, linkTapAction(kind, LinkHealth.OFF, allOn, wifiDirectSupported = true))
            assertEquals(LinkTapAction.TOGGLE, linkTapAction(kind, LinkHealth.PROBLEM, allOn, wifiDirectSupported = true))
        }
    }

    @Test
    fun radiosOffAskForTheRadioFirst() {
        val btOff = allOn.copy(bluetoothOn = false)
        assertEquals(LinkTapAction.ENABLE_BLUETOOTH, linkTapAction(TransportKind.BLE, LinkHealth.PROBLEM, btOff, true))
        val wifiOff = allOn.copy(wifiOn = false)
        assertEquals(LinkTapAction.OPEN_WIFI_SETTINGS, linkTapAction(TransportKind.HUB, LinkHealth.OFF, wifiOff, true))
        assertEquals(LinkTapAction.OPEN_WIFI_SETTINGS, linkTapAction(TransportKind.WIFI_DIRECT, LinkHealth.PROBLEM, wifiOff, true))
        // The relay doesn't need Wi-Fi (mobile data works).
        assertEquals(LinkTapAction.TOGGLE, linkTapAction(TransportKind.INTERNET, LinkHealth.OFF, wifiOff, true))
    }

    @Test
    fun unsupportedWifiDirectOnlyExplains() {
        assertEquals(LinkTapAction.SHOW_DETAILS, linkTapAction(TransportKind.WIFI_DIRECT, LinkHealth.OFF, allOn, wifiDirectSupported = false))
    }
}
