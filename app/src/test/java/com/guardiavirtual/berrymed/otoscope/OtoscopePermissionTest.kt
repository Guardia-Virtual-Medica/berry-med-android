// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import com.guardiavirtual.berrymed.otoscope.OtoscopePermission.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class OtoscopePermissionTest {
    @Test
    fun withTheHubAndAKnownPortThePortIsCycled() {
        assertEquals(Step.CYCLE_PORT, OtoscopePermission.next(present = true, granted = false, hubUsable = true, port = 1))
    }

    @Test
    fun withoutTheHubItFallsBackToRequestPermission() {
        assertEquals(Step.REQUEST, OtoscopePermission.next(present = true, granted = false, hubUsable = false, port = 1))
        assertEquals(Step.REQUEST, OtoscopePermission.next(present = true, granted = false, hubUsable = true, port = null))
        assertEquals(Step.REQUEST, OtoscopePermission.next(present = true, granted = false, hubUsable = true, port = 4))
    }

    @Test
    fun withPermissionOrUnpluggedNothingHappens() {
        // Con permiso puede estar publicando o abierto en el Panel Local: nunca se le apaga el puerto.
        assertEquals(Step.NOTHING, OtoscopePermission.next(present = true, granted = true, hubUsable = true, port = 1))
        assertEquals(Step.NOTHING, OtoscopePermission.next(present = false, granted = false, hubUsable = true, port = 1))
    }
}
