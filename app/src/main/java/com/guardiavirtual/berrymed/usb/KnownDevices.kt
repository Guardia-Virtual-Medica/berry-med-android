// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

/**
 * Los equipos del tótem que se reconocen por fabricante y producto USB. Los
 * identificadores salen de los dumps que se tomaron en Windows con cada uno.
 */
object KnownDevices {
    const val YKUSH_VENDOR = 0x04D8
    const val YKUSH3_PRODUCT = 0xF11B

    const val OTOSCOPE_VENDOR = 0x349C
    const val OTOSCOPE_PRODUCT = 0x3301

    const val STETHO_ADAPTER_VENDOR = 0x3302
    const val STETHO_ADAPTER_PRODUCT = 0x29E3

    const val LOGITECH_VENDOR = 0x046D

    fun isYkush3(vendorId: Int, productId: Int): Boolean =
        vendorId == YKUSH_VENDOR && productId == YKUSH3_PRODUCT

    fun isOtoscope(vendorId: Int, productId: Int): Boolean =
        vendorId == OTOSCOPE_VENDOR && productId == OTOSCOPE_PRODUCT

    /** Cómo se llama el equipo en el tótem, o null si no es uno conocido. */
    fun name(vendorId: Int, productId: Int): String? = when {
        isYkush3(vendorId, productId) -> "hub YKUSH3"
        vendorId == UsbHub.STM32_VENDOR -> "plaqueta PM6750"
        isOtoscope(vendorId, productId) -> "otoscopio"
        vendorId == STETHO_ADAPTER_VENDOR && productId == STETHO_ADAPTER_PRODUCT -> "adaptador del estetoscopio"
        vendorId == LOGITECH_VENDOR -> "auricular Logitech"
        else -> null
    }
}
