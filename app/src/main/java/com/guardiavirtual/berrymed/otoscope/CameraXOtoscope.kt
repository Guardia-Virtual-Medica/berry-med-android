// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

/**
 * Camino Camera2: la tablet lista el otoscopio como cámara externa y se abre
 * con CameraX, igual que la cámara de la tablet. Usa el mismo
 * `ProcessCameraProvider` que la tarjeta de cámara, que mientras tanto está
 * en pausa. Todo en el hilo de la pantalla, como pide CameraX; cómo se abre
 * está en [ExternalCamera], que le pide 30 cuadros si la cámara los ofrece.
 *
 * `PreviewView` no avisa cuándo dibuja, así que los cuadros por segundo se
 * cuentan con las capturas que completa Camera2: es lo que entrega la cámara,
 * no lo que se ve, pero alcanza para saber si viene a 30 o a saltos.
 */
class CameraXOtoscope(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
) : OtoscopeSource {
    override val label = "por Camera2"
    private var provider: ProcessCameraProvider? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false

    override fun open(done: (String?) -> Unit, onRate: (Double) -> Unit) {
        closed = false
        previewView.visibility = View.VISIBLE
        ExternalCamera(activity).bind(
            owner = activity,
            useCase = { info ->
                val builder = Preview.Builder().setResolutionSelector(ExternalCamera.RESOLUTION)
                ExternalCamera.aimFps(builder, info).setSessionCaptureCallback(rateCallback(onRate))
                builder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            },
            closed = { closed },
        ) { opened, reason ->
            provider = opened
            done(reason)
        }
    }

    override fun close(done: () -> Unit) {
        closed = true
        provider?.unbindAll()
        provider = null
        previewView.visibility = View.GONE
        done()
    }

    override fun snapshot(): Bitmap? = previewView.bitmap

    /** Cuenta las capturas en el hilo de Camera2 y avisa la cadencia en el de la pantalla. */
    private fun rateCallback(onRate: (Double) -> Unit) = object : CameraCaptureSession.CaptureCallback() {
        private val meter = OtoscopeFps.Meter()

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (closed) return
            val rate = meter.frame(System.nanoTime()) ?: return
            main.post { if (!closed) onRate(rate) }
        }
    }
}
