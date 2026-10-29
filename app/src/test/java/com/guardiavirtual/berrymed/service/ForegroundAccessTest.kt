// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.service.ForegroundAccess.CAMERA
import com.guardiavirtual.berrymed.service.ForegroundAccess.CONNECTED_DEVICE
import com.guardiavirtual.berrymed.service.ForegroundAccess.MICROPHONE
import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundAccessTest {
    @Test
    fun fromTheBackgroundNothingIsAdded() {
        assertEquals(CONNECTED_DEVICE, ForegroundAccess.next(0, visible = false, cameraGranted = true, microphoneGranted = true))
        assertEquals(CONNECTED_DEVICE, ForegroundAccess.next(CONNECTED_DEVICE, visible = false, cameraGranted = true, microphoneGranted = true))
    }

    @Test
    fun withAScreenVisibleCameraAndMicrophoneAreAddedIfGranted() {
        assertEquals(
            CONNECTED_DEVICE or CAMERA or MICROPHONE,
            ForegroundAccess.next(CONNECTED_DEVICE, visible = true, cameraGranted = true, microphoneGranted = true),
        )
        assertEquals(
            CONNECTED_DEVICE or MICROPHONE,
            ForegroundAccess.next(CONNECTED_DEVICE, visible = true, cameraGranted = false, microphoneGranted = true),
        )
    }

    @Test
    fun theTypesNeverGoDown() {
        val all = CONNECTED_DEVICE or CAMERA or MICROPHONE
        assertEquals(all, ForegroundAccess.next(all, visible = false, cameraGranted = false, microphoneGranted = false))
        assertEquals(all, ForegroundAccess.next(all, visible = true, cameraGranted = false, microphoneGranted = false))
    }

    @Test
    fun theDescriptionNamesEachType() {
        assertEquals("connectedDevice|camera|microphone", ForegroundAccess.describe(CONNECTED_DEVICE or CAMERA or MICROPHONE))
        assertEquals("connectedDevice", ForegroundAccess.describe(CONNECTED_DEVICE))
    }
}
