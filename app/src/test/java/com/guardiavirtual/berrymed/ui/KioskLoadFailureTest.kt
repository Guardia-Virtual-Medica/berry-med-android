// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KioskLoadFailureTest {
    @Test
    fun aConnectionErrorOfThePageCounts() {
        assertTrue(KioskLoadFailure.matters(isMainFrame = true))
    }

    @Test
    fun aConnectionErrorOfAResourceDoesNotCount() {
        assertFalse(KioskLoadFailure.matters(isMainFrame = false))
    }

    @Test
    fun aServerErrorOfThePageCounts() {
        assertTrue(KioskLoadFailure.matters(isMainFrame = true, httpStatus = 503))
    }

    @Test
    fun aNotFoundOfThePageDoesNotCount() {
        assertFalse(KioskLoadFailure.matters(isMainFrame = true, httpStatus = 404))
    }

    @Test
    fun aServerErrorOfAResourceDoesNotCount() {
        assertFalse(KioskLoadFailure.matters(isMainFrame = false, httpStatus = 500))
    }
}
