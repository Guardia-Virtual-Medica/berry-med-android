// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.graphics.Bitmap
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
 * está en [ExternalCamera].
 */
class CameraXOtoscope(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
) : OtoscopeSource {
    override val label = "por Camera2"
    private var provider: ProcessCameraProvider? = null
    private var closed = false

    override fun open(done: (String?) -> Unit) {
        closed = false
        previewView.visibility = View.VISIBLE
        ExternalCamera(activity).bind(
            owner = activity,
            useCase = {
                Preview.Builder().setResolutionSelector(ExternalCamera.RESOLUTION).build().also {
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
}
