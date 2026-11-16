// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

/**
 * La librería del otoscopio (libUVCCamera de AUSBC 3.2.7) no inicializa los
 * hilos de su vista previa. Si la imagen no llega a arrancar (el otoscopio no
 * contesta la negociación), al cerrarla espera un hilo que nunca creó: con
 * basura en ese campo Android aborta el proceso, y con él el kiosco y la
 * consulta. Con cero en ese campo lo saltea.
 *
 * Por eso el manifiesto pide `nativeHeapZeroInitialized`: la memoria nativa
 * llega en cero. Esto comprueba al arrancar que la tablet lo cumple, para que
 * quede en el log.
 */
object NativeHeap {
    private const val BLOCKS = 32

    /** El tamaño del objeto de la vista previa en arm64: se prueba con bloques iguales. */
    private const val SIZE = 424L

    /** Si la memoria nativa recién pedida viene en cero; null si no se pudo probar. */
    fun zeroed(): Boolean? = try {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val type = unsafe.javaClass
        val long = Long::class.javaPrimitiveType
        val alloc = type.getMethod("allocateMemory", long)
        val free = type.getMethod("freeMemory", long)
        val fill = type.getMethod("setMemory", long, long, Byte::class.javaPrimitiveType)
        val read = type.getMethod("getLong", long)
        // Se ensucian y se sueltan bloques: los que se piden después reusan esa memoria.
        val dirty = LongArray(BLOCKS) { alloc.invoke(unsafe, SIZE) as Long }
        dirty.forEach { fill.invoke(unsafe, it, SIZE, 0x55.toByte()) }
        dirty.forEach { free.invoke(unsafe, it) }
        val fresh = LongArray(BLOCKS) { alloc.invoke(unsafe, SIZE) as Long }
        val clean = fresh.all { block -> (0 until SIZE step 8).all { read.invoke(unsafe, block + it) == 0L } }
        fresh.forEach { free.invoke(unsafe, it) }
        clean
    } catch (_: Throwable) {
        null
    }
}
