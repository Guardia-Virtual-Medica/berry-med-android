// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Abrir el otoscopio como cámara externa con CameraX, para la tarjeta del
 * Panel Local ([CameraXOtoscope], con una vista previa) y para la
 * teleconsulta ([CameraXOtoscopeFrames], con un análisis de cuadros). Todo en
 * el hilo de la pantalla, como pide CameraX.
 */
@OptIn(ExperimentalLensFacing::class, ExperimentalCamera2Interop::class)
internal class ExternalCamera(private val context: Context) {
    val selector: CameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build()

    /**
     * Une el caso de uso que arma [useCase] a [owner] con la cámara externa.
     * [useCase] recibe la información de esa cámara (o null si no se pudo
     * leer), para pedirle la cadencia con [aimFps]. [done] llega en el hilo de la pantalla con el proveedor si abrió, o null
     * y el motivo. Si [closed] pasa a true mientras tanto, no llega nada.
     */
    fun bind(
        owner: LifecycleOwner,
        useCase: (CameraInfo?) -> UseCase,
        closed: () -> Boolean,
        done: (ProcessCameraProvider?, String?) -> Unit,
    ) {
        withProvider(closed, done) { provider -> bind(provider, owner, useCase, retried = false, closed, done) }
    }

    private fun withProvider(
        closed: () -> Boolean,
        done: (ProcessCameraProvider?, String?) -> Unit,
        use: (ProcessCameraProvider) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed()) return@addListener
            val provider = try {
                future.get()
            } catch (error: Exception) {
                done(null, "No se pudo iniciar la cámara: ${error.message ?: error.javaClass.simpleName}")
                return@addListener
            }
            use(provider)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * CameraX arma su lista de cámaras al iniciarse. El otoscopio se enchufa al
     * encender el puerto, después, así que la lista puede no tenerlo o tenerlo
     * con el id de un encendido anterior: en ese caso no lo ve o el bind falla.
     * Las dos cosas se resuelven igual, reiniciándolo una vez.
     */
    private fun bind(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        useCase: (CameraInfo?) -> UseCase,
        retried: Boolean,
        closed: () -> Boolean,
        done: (ProcessCameraProvider?, String?) -> Unit,
    ) {
        if (!hasExternal(provider)) {
            if (retried) {
                done(null, "Android lista el otoscopio como cámara pero CameraX no lo ve.")
            } else {
                restart(provider, owner, useCase, closed, done)
            }
            return
        }
        try {
            val case = useCase(externalInfo(provider))
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, case)
            done(provider, null)
        } catch (error: Exception) {
            if (retried) {
                done(null, "No se pudo abrir el otoscopio: ${error.message ?: error.javaClass.simpleName}")
            } else {
                restart(provider, owner, useCase, closed, done)
            }
        }
    }

    // shutdownAsync está marcado para pruebas, pero es público y es la única forma de que CameraX vuelva a listar las cámaras.
    @SuppressLint("VisibleForTests")
    private fun restart(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        useCase: (CameraInfo?) -> UseCase,
        closed: () -> Boolean,
        done: (ProcessCameraProvider?, String?) -> Unit,
    ) {
        provider.shutdownAsync().addListener({
            if (closed()) return@addListener
            withProvider(closed, done) { fresh -> bind(fresh, owner, useCase, retried = true, closed, done) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun hasExternal(provider: ProcessCameraProvider): Boolean = try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }

    private fun externalInfo(provider: ProcessCameraProvider): CameraInfo? = try {
        selector.filter(provider.availableCameraInfos).firstOrNull()
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Le pide a la cámara 30 cuadros por segundo si los ofrece, con el
         * rango que elige [OtoscopeFps.camera2Range] entre los que declara
         * [info]. Sin rango que llegue a 30, o sin [info], no se toca nada y
         * la cadencia la elige el HAL, como antes. Devuelve el `Extender` para
         * sumarle otras opciones de Camera2.
         */
        fun <T> aimFps(builder: ExtendableBuilder<T>, info: CameraInfo?): Camera2Interop.Extender<T> {
            val extender = Camera2Interop.Extender(builder)
            val available = try {
                info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            } catch (_: Exception) {
                null
            }.orEmpty().map { it.lower to it.upper }
            OtoscopeFps.camera2Range(available)?.let { (low, high) ->
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(low, high))
            }
            return extender
        }

        /** 800×800 o la más cercana que ofrezca la cámara: ver [OtoscopeResolution]. */
        val RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(OtoscopeResolution.TARGET, OtoscopeResolution.TARGET),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .setResolutionFilter { sizes, _ ->
                OtoscopeResolution.byPreference(sizes.map { OtoscopeResolution.Size(it.width, it.height) })
                    .map { Size(it.width, it.height) }
            }
            .build()
    }
}
