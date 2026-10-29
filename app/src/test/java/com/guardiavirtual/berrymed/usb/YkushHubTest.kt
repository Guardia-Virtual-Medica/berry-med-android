// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.ykushPortOrDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YkushHubTest {
    @Test
    fun thePortGoesInTheLowBitsAndTheActionInTheHighOnes() {
        assertEquals(0x11.toByte(), YkushHub.command(port = 1, on = true))
        assertEquals(0x13.toByte(), YkushHub.command(port = 3, on = true))
        assertEquals(0x02.toByte(), YkushHub.command(port = 2, on = false))
        assertEquals(0x21.toByte(), YkushHub.statusCommand(port = 1))
        assertEquals(0x2A.toByte(), YkushHub.STATUS_ALL)
    }

    @Test
    fun thePacketIsSixtyFourBytesWithTheCommandFirst() {
        val packet = YkushHub.packet(YkushHub.command(port = 2, on = true))
        assertEquals(64, packet.size)
        assertEquals(0x12.toByte(), packet[0])
        assertTrue(packet.drop(1).all { it == 0.toByte() })
    }

    @Test
    fun theHubAnswersOneWhenItAccepted() {
        assertTrue(YkushHub.ok(byteArrayOf(0x01, 0x11)))
        assertFalse(YkushHub.ok(byteArrayOf(0x00, 0x11)))
        assertFalse(YkushHub.ok(byteArrayOf()))
    }

    @Test
    fun aPortAboveSixteenIsOnAndAShortReplyLeavesTheRestUnknown() {
        assertEquals(listOf(true, false, true), YkushHub.statesFrom(byteArrayOf(0x01, 0x11, 0x01, 0x13)))
        assertEquals(listOf(false, null, null), YkushHub.statesFrom(byteArrayOf(0x01, 0x01)))
    }

    @Test
    fun eachPortNamesItsDeviceAndASharedPortNamesBoth() {
        assertEquals(listOf("otoscopio", "estetoscopio", "auricular"), BerryConfig().ykushPortRoles())
        val shared = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushHeadsetPort = 3)
        assertEquals(listOf("otoscopio y estetoscopio", "libre", "auricular"), shared.ykushPortRoles())
    }

    @Test
    fun aPortTypedByHandMustBeOneToThree() {
        assertEquals(2, ykushPortOrDefault(" 2 ", default = 1))
        assertEquals(1, ykushPortOrDefault("4", default = 1))
        assertEquals(3, ykushPortOrDefault("", default = 3))
        assertEquals(3, ykushPortOrDefault(null, default = 3))
    }
}
