// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Size
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Camino Camera2: la tablet lista el otoscopio como cámara externa y se abre
 * con CameraX, igual que la cámara de la tablet. Usa el mismo
 * `ProcessCameraProvider` que la tarjeta de cámara, que mientras tanto está
 * en pausa. Todo en el hilo de la pantalla, como pide CameraX.
 */
@OptIn(ExperimentalLensFacing::class)
class CameraXOtoscope(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val probe: OtoscopeProbe,
) : OtoscopeSource {
    override val label = "por Camera2"
    private var provider: ProcessCameraProvider? = null
    private var closed = false

    private val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build()

    override fun isAvailable(): Boolean = probe.cameraSeen()

    override fun open(done: (String?) -> Unit) {
        closed = false
        previewView.visibility = View.VISIBLE
        withProvider(done) { provider -> bind(provider, retried = false, done) }
    }

    private fun withProvider(done: (String?) -> Unit, use: (ProcessCameraProvider) -> Unit) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (closed) return@addListener
            val provider = try {
                future.get()
            } catch (error: Exception) {
                done("No se pudo iniciar la cámara: ${error.message ?: error.javaClass.simpleName}")
                return@addListener
            }
            use(provider)
        }, ContextCompat.getMainExecutor(activity))
    }

    // shutdownAsync está marcado para pruebas, pero es público y es la única forma de que CameraX vuelva a listar las cámaras.
    @SuppressLint("VisibleForTests")
    private fun bind(provider: ProcessCameraProvider, retried: Boolean, done: (String?) -> Unit) {
        if (!hasExternal(provider)) {
            if (retried) {
                done("Android lista el otoscopio como cámara pero CameraX no lo ve.")
                return
            }
            // CameraX arma su lista de cámaras al iniciarse. El otoscopio se enchufa
            // al encender el puerto, después: hay que reiniciarlo para que lo vea.
            provider.shutdownAsync().addListener({
                if (closed) return@addListener
                withProvider(done) { fresh -> bind(fresh, retried = true, done) }
            }, ContextCompat.getMainExecutor(activity))
            return
        }
        try {
            val preview = Preview.Builder().setResolutionSelector(RESOLUTION).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(activity, selector, preview)
            this.provider = provider
            done(null)
        } catch (error: Exception) {
            done("No se pudo abrir el otoscopio: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun hasExternal(provider: ProcessCameraProvider): Boolean = try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }

    override fun close(done: () -> Unit) {
        closed = true
        provider?.unbindAll()
        provider = null
        previewView.visibility = View.GONE
        done()
    }

    override fun snapshot(): Bitmap? = previewView.bitmap

    companion object {
        /** 800×800 o la más cercana que ofrezca la cámara: ver [OtoscopeResolution]. */
        private val RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
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
