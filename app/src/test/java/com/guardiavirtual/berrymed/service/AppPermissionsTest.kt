// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPermissionsTest {
    private fun grantedOnly(vararg permissions: String): (String) -> Boolean = { it in permissions }

    @Test
    fun allTogetherWhenNothingIsGranted() {
        assertEquals(
            listOf(
                AppPermissions.CAMERA,
                AppPermissions.RECORD_AUDIO,
                AppPermissions.FINE_LOCATION,
                AppPermissions.COARSE_LOCATION,
                AppPermissions.NOTIFICATIONS,
            ),
            AppPermissions.missing(34, grantedOnly()),
        )
    }

    @Test
    fun notificationsOnlyFromAndroid13() {
        assertFalse(AppPermissions.NOTIFICATIONS in AppPermissions.missing(32, grantedOnly()))
        assertTrue(AppPermissions.NOTIFICATIONS in AppPermissions.missing(33, grantedOnly()))
    }

    @Test
    fun onlyWhatIsMissing() {
        val granted = grantedOnly(
            AppPermissions.CAMERA,
            AppPermissions.FINE_LOCATION,
            AppPermissions.COARSE_LOCATION,
            AppPermissions.NOTIFICATIONS,
        )
        assertEquals(listOf(AppPermissions.RECORD_AUDIO), AppPermissions.missing(34, granted))
    }

    @Test
    fun preciseLocationGoesWithTheApproximateOne() {
        // Con sólo la aproximada dada, se piden las dos: la precisa sola Android la ignora.
        val approximate = grantedOnly(
            AppPermissions.CAMERA,
            AppPermissions.RECORD_AUDIO,
            AppPermissions.COARSE_LOCATION,
            AppPermissions.NOTIFICATIONS,
        )
        assertEquals(
            listOf(AppPermissions.FINE_LOCATION, AppPermissions.COARSE_LOCATION),
            AppPermissions.missing(34, approximate),
        )
        assertEquals(
            listOf(AppPermissions.FINE_LOCATION, AppPermissions.COARSE_LOCATION),
            AppPermissions.manifest(AppPermission.LOCATION),
        )
    }

    @Test
    fun thePanelRows() {
        assertEquals(AppPermission.entries.toSet(), AppPermissions.missingForPanel(grantedOnly()))
        assertEquals(
            setOf(AppPermission.LOCATION),
            AppPermissions.missingForPanel(grantedOnly(AppPermissions.CAMERA, AppPermissions.RECORD_AUDIO, AppPermissions.COARSE_LOCATION)),
        )
        assertEquals(
            emptySet(),
            AppPermissions.missingForPanel(grantedOnly(AppPermissions.CAMERA, AppPermissions.RECORD_AUDIO, AppPermissions.FINE_LOCATION)),
        )
    }

    @Test
    fun settingsOnlyFromATapAndWhenAndroidNoLongerAsks() {
        assertTrue(AppPermissions.openSettingsAfter(fromTap = true, deniedBlocked = listOf(true)))
        assertTrue(AppPermissions.openSettingsAfter(fromTap = true, deniedBlocked = listOf(false, true)))
        // Rechazado una vez: se puede volver a pedir.
        assertFalse(AppPermissions.openSettingsAfter(fromTap = true, deniedBlocked = listOf(false)))
        // Aceptado.
        assertFalse(AppPermissions.openSettingsAfter(fromTap = true, deniedBlocked = emptyList()))
        // El pedido de al abrir nunca manda a los ajustes.
        assertFalse(AppPermissions.openSettingsAfter(fromTap = false, deniedBlocked = listOf(true)))
    }
}
