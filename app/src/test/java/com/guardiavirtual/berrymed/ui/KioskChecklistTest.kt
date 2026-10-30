// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KioskChecklistTest {
    private val ready = KioskChecklistInput(
        cameraGranted = true,
        microphoneGranted = true,
        berryMedIsHome = true,
        sdkInt = 35,
        serviceCamera = true,
        serviceMicrophone = true,
        ignoringBatteryOptimizations = true,
        kioskUrlSaved = true,
    )

    private fun item(input: KioskChecklistInput, check: KioskCheck) = KioskChecklist.items(input).single { it.check == check }

    @Test
    fun theItemsComeInTheOrderOfTheScreen() {
        assertEquals(
            listOf(
                KioskCheck.PERMISSIONS,
                KioskCheck.HOME,
                KioskCheck.SERVICE,
                KioskCheck.BATTERY,
                KioskCheck.OTOSCOPE_USB,
                KioskCheck.TOTEM,
            ),
            KioskChecklist.items(ready).map { it.check },
        )
    }

    @Test
    fun aReadyTabletIsAllGreenWithoutButtonsButWhatBerryMedCannotKnow() {
        val items = KioskChecklist.items(ready)
        val known = items.filter { it.check != KioskCheck.TOTEM && it.check != KioskCheck.OTOSCOPE_USB }
        assertTrue(known.all { it.done == true && !it.action })
        val totem = item(ready, KioskCheck.TOTEM)
        assertTrue(totem.action)
        assertTrue(totem.actionEnabled)
    }

    @Test
    fun permissionsNeedBothCameraAndMicrophone() {
        for ((camera, microphone) in listOf(false to true, true to false, false to false)) {
            val permissions = item(ready.copy(cameraGranted = camera, microphoneGranted = microphone), KioskCheck.PERMISSIONS)
            assertEquals(false, permissions.done)
            assertTrue(permissions.action)
        }
    }

    @Test
    fun anotherHomeAppIsAmberWithItsButton() {
        val home = item(ready.copy(berryMedIsHome = false), KioskCheck.HOME)
        assertEquals(false, home.done)
        assertTrue(home.action)
    }

    @Test
    fun theServiceNeedsCameraAndMicrophoneFromAndroid11AndNeverHasAButton() {
        for ((camera, microphone) in listOf(false to true, true to false, false to false)) {
            val service = item(ready.copy(serviceCamera = camera, serviceMicrophone = microphone), KioskCheck.SERVICE)
            assertEquals(false, service.done)
            assertFalse(service.action)
        }
        assertEquals(false, item(ready.copy(sdkInt = 30, serviceCamera = false), KioskCheck.SERVICE).done)
    }

    @Test
    fun beforeAndroid11TheServiceCountsAsDone() {
        val old = ready.copy(sdkInt = 29, serviceCamera = false, serviceMicrophone = false)
        assertEquals(true, item(old, KioskCheck.SERVICE).done)
    }

    @Test
    fun batteryOptimizationIsAmberWithItsButton() {
        val battery = item(ready.copy(ignoringBatteryOptimizations = false), KioskCheck.BATTERY)
        assertEquals(false, battery.done)
        assertTrue(battery.action)
    }

    @Test
    fun theTotemCannotBeDetectedAndOpeningTheKioskNeedsASavedUrl() {
        val totem = item(ready.copy(kioskUrlSaved = false), KioskCheck.TOTEM)
        assertNull(totem.done)
        assertTrue(totem.action)
        assertFalse(totem.actionEnabled)
    }

    @Test
    fun theOtoscopePermissionCannotBeDetectedAndTheButtonIsAlwaysThere() {
        for (input in listOf(ready, ready.copy(kioskUrlSaved = false, cameraGranted = false))) {
            val otoscope = item(input, KioskCheck.OTOSCOPE_USB)
            assertNull(otoscope.done)
            assertTrue(otoscope.action)
            assertTrue(otoscope.actionEnabled)
        }
    }

    @Test
    fun oneMissingItemDoesNotTouchTheOthers() {
        val items = KioskChecklist.items(ready.copy(berryMedIsHome = false))
        assertEquals(listOf(KioskCheck.HOME), items.filter { it.done == false }.map { it.check })
    }
}
