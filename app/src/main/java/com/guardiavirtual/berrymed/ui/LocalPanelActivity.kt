// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.audio.StethoDsp
import com.guardiavirtual.berrymed.config.pinAccepted
import com.guardiavirtual.berrymed.databinding.ActivityLocalPanelBinding
import com.guardiavirtual.berrymed.otoscope.CameraXOtoscope
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.otoscope.OtoscopeSource
import com.guardiavirtual.berrymed.otoscope.UvcOtoscope
import com.guardiavirtual.berrymed.service.LocalSnapshot
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.ui.panel.FreezeFrame
import com.guardiavirtual.berrymed.ui.panel.OtoscopeZoom
import com.guardiavirtual.berrymed.ui.panel.PanelFormat
import kotlin.concurrent.thread

/**
 * Panel Local: atención presencial, con quien atiende al lado del paciente.
 * Muestra todos los instrumentos en vivo y no identifica, no guarda ni envía
 * nada. Mientras está abierto el servicio no manda POST ni atiende órdenes de
 * Pusher; al irse de la pantalla todo vuelve a la normalidad.
 */
class LocalPanelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalPanelBinding
    private var unlocked = false
    private var lastRenderAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraFreeze: FreezeFrame
    private lateinit var otoscopeFreeze: FreezeFrame

    /** La imagen del otoscopio abierta en la tarjeta, por Camera2 o por UVC. */
    private var otoscope: OtoscopeSource? = null

    /** Camera2 no garantiza dos cámaras abiertas a la vez: con el otoscopio encendido, la de la tablet espera. */
    private var cameraPaused = false

    /** El cuadro del otoscopio está en el centro de la pantalla en vez de en la tarjeta. */
    private var zoomed = false

    /** Dónde y cómo va el cuadro en la tarjeta, para devolverlo igual al reducir. */
    private var stageIndex = 0
    private lateinit var stageParams: ViewGroup.LayoutParams

    /** Atrás con el otoscopio ampliado sólo reduce; con el panel normal hace lo de siempre. */
    private val zoomBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeZoom()
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!unlocked) return
            binding.ecgPaper.tick()
            binding.spo2Wave.tick()
            binding.respWave.tick()
            if (binding.stethoCard.visibility == View.VISIBLE) {
                binding.stethoWave.tick()
                binding.stethoLevel.progress = StethoDsp.levelPercent(runtime().stetho.level)
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastRenderAt >= RENDER_MS) {
                lastRenderAt = now
                render(runtime().localSnapshot())
            }
            binding.ecgPaper.postOnAnimation(this)
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!unlocked) return@registerForActivityResult
        if (granted) bindCamera() else binding.cameraMessage.setText(R.string.local_camera_denied)
    }

    private val otoscopeCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!unlocked) return@registerForActivityResult
        if (granted) otoscopeOn() else setOtoscopeMessage(getString(R.string.local_otoscope_denied))
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!unlocked) return@registerForActivityResult
        if (granted) listen() else binding.stethoMessage.setText(R.string.local_stetho_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Si la tablet se encendió y se abrió directo el panel, el servicio puede no estar corriendo.
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))

        val live = runtime().live
        binding.ecgPaper.attach(live)
        binding.spo2Wave.attach(live.spo2)
        binding.respWave.attach(live.resp)
        // La plaqueta no documenta la frecuencia de estas ondas: se eligieron a ojo para ver unos pocos ciclos.
        binding.spo2Wave.samplesPerScreen = SPO2_SAMPLES
        binding.respWave.samplesPerScreen = RESP_SAMPLES
        binding.respWave.traceColor = getColor(R.color.vital_on)
        binding.stethoWave.attach(live.stetho)
        // Dos segundos a 500 por segundo: entran dos o tres latidos.
        binding.stethoWave.samplesPerScreen = STETHO_SAMPLES
        binding.stethoWave.traceColor = getColor(R.color.status_live)

        binding.enterButton.setOnClickListener { tryUnlock() }
        binding.pinInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }
        binding.lockBackButton.setOnClickListener { finish() }
        binding.exitButton.setOnClickListener { finish() }

        binding.speedButton.setOnClickListener {
            val slow = binding.ecgPaper.mmPerSecond == FAST_MM_S
            binding.ecgPaper.mmPerSecond = if (slow) SLOW_MM_S else FAST_MM_S
            binding.speedButton.setText(if (slow) R.string.local_speed_12 else R.string.local_speed_25)
        }
        binding.gainGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            binding.ecgPaper.displayGain = when (id) {
                R.id.gainHalf -> 0.5f
                R.id.gainTwo -> 2f
                else -> 1f
            }
            binding.ecgPaper.invalidate()
        }

        binding.measureButton.setOnClickListener { nibp { runtime().localNibpStart() } }
        binding.stopButton.setOnClickListener { nibp { runtime().localNibpStop() } }
        binding.cameraSwitchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            cameraFreeze.unfreeze()
            bindCamera()
        }
        cameraFreeze = FreezeFrame(
            binding.frozenImage,
            listOf(binding.freezeButton),
            listOf(binding.cameraMessage),
            R.string.local_freeze,
            R.string.local_resume,
            R.string.local_camera_frozen,
        )
        otoscopeFreeze = FreezeFrame(
            binding.otoscopeFrozen,
            listOf(binding.otoscopeFreezeButton, binding.otoscopeZoomFreezeButton),
            listOf(binding.otoscopeMessage, binding.otoscopeZoomMessage),
            R.string.local_freeze,
            R.string.local_resume,
            R.string.local_camera_frozen,
        )
        binding.freezeButton.setOnClickListener {
            if (cameraFreeze.frozen) cameraFreeze.unfreeze() else cameraFreeze.freeze(binding.cameraPreview.bitmap)
        }
        binding.otoscopePowerButton.setOnClickListener { otoscopePower() }
        binding.otoscopeFreezeButton.setOnClickListener { toggleOtoscopeFreeze() }
        stageIndex = binding.otoscopeCard.indexOfChild(binding.otoscopeStage)
        stageParams = binding.otoscopeStage.layoutParams
        binding.otoscopeZoomButton.setOnClickListener { openZoom() }
        // Tocar la imagen en la tarjeta también la amplía, si se puede.
        binding.otoscopeStage.setOnClickListener { if (binding.otoscopeZoomButton.isEnabled) openZoom() }
        binding.otoscopeZoomFreezeButton.setOnClickListener { toggleOtoscopeFreeze() }
        // Apagar desde el ampliado: releaseOtoscope cierra el ampliado antes de soltar la cámara.
        binding.otoscopeZoomPowerButton.setOnClickListener { otoscopeOff(R.string.local_otoscope_done) }
        binding.otoscopeZoomCloseButton.setOnClickListener { closeZoom() }
        onBackPressedDispatcher.addCallback(this, zoomBack)

        binding.stethoListenButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                listen()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.stethoStopButton.setOnClickListener {
            runtime().localStethoStop { text -> runOnUiThread { binding.stethoMessage.text = text } }
        }
        binding.stethoGainButton.setOnClickListener {
            val stetho = runtime().stetho
            stetho.gain = StethoDsp.nextGain(stetho.gain)
            showStethoGain()
        }
        showStethoGain()
    }

    /** Cada vez que el panel vuelve al frente pide el PIN de nuevo. */
    override fun onStart() {
        super.onStart()
        hideSystemBars()
        lock()
    }

    /** Al cerrar con Salir o el gesto de atrás el modo se corta ya: onStop puede tardar segundos. */
    override fun onPause() {
        if (isFinishing) {
            releaseOtoscope()
            runtime().stopLocal()
        }
        super.onPause()
    }

    override fun onStop() {
        binding.ecgPaper.removeCallbacks(frame)
        // Primero la cámara del otoscopio: stopLocal apaga su puerto, y antes tiene que estar suelta.
        releaseOtoscope()
        cameraProvider?.unbindAll()
        runtime().stopLocal()
        lock()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Un diálogo (el permiso de la cámara, el teclado) puede devolver las barras.
        if (hasFocus) hideSystemBars()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun lock() {
        unlocked = false
        closeZoom()
        binding.lock.visibility = View.VISIBLE
        binding.pinInput.setText("")
        binding.lockMessage.text = ""
        cameraFreeze.unfreeze()
        otoscopeFreeze.unfreeze()
    }

    private fun tryUnlock() {
        val entered = binding.pinInput.text?.toString()
        if (!pinAccepted(entered, runtime().config.localPanelPin)) {
            binding.pinInput.setText("")
            binding.lockMessage.setText(R.string.local_pin_wrong)
            return
        }
        val blocked = runtime().startLocal()
        if (blocked != null) {
            binding.pinInput.setText("")
            binding.lockMessage.text = blocked
            return
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.pinInput.windowToken, 0)
        binding.lock.visibility = View.GONE
        binding.nibpMessage.text = ""
        // startLocal vació las ondas: las pantallas arrancan en blanco y leen desde cero.
        binding.ecgPaper.restart()
        binding.spo2Wave.restart()
        binding.respWave.restart()
        binding.stethoWave.restart()
        binding.stethoMessage.text = ""
        setOtoscopeMessage("")
        unlocked = true
        lastRenderAt = 0L
        binding.ecgPaper.postOnAnimation(frame)
        startCamera()
    }

    /** Medir y Parar escriben en el puerto serie: fuera del hilo de la pantalla. */
    private fun nibp(action: () -> String) {
        thread(name = "berry-local-nibp") {
            val text = action()
            runOnUiThread { binding.nibpMessage.text = text }
        }
    }

    private fun otoscopePower() {
        if (otoscope != null || runtime().otoscopeState != OtoscopePower.State.OFF) {
            otoscopeOff(R.string.local_otoscope_done)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // Camera2 y UVC piden el permiso de la cámara: en Android 9 en adelante también para un USB de video.
            otoscopeOn()
        } else {
            otoscopeCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleOtoscopeFreeze() {
        if (otoscopeFreeze.frozen) otoscopeFreeze.unfreeze() else otoscopeFreeze.freeze(otoscope?.snapshot())
    }

    /** El mensaje del otoscopio va en la tarjeta y, repetido, debajo del ampliado. */
    private fun setOtoscopeMessage(text: CharSequence) {
        binding.otoscopeMessage.text = text
        binding.otoscopeZoomMessage.text = text
    }

    /**
     * Ampliar: el cuadro del otoscopio se muda de la tarjeta al centro de la
     * pantalla. Es la misma vista, así que la imagen sigue y Congelar también
     * (la imagen congelada viaja con el cuadro). En la tarjeta queda un hueco
     * del mismo tamaño para que la columna no salte.
     */
    private fun openZoom() {
        if (zoomed) return
        val stage = binding.otoscopeStage
        val root = binding.root
        val side = OtoscopeZoom.stageSide(root.width, root.height, dp(ZOOM_MARGIN_DP), dp(ZOOM_BUTTONS_DP))
        binding.otoscopeCard.removeView(stage)
        binding.otoscopePlaceholder.visibility = View.VISIBLE
        binding.otoscopeZoomBox.addView(stage, 0, LinearLayout.LayoutParams(side, side))
        binding.otoscopeZoom.visibility = View.VISIBLE
        // El desenfoque es de Android 12 en adelante; antes queda sólo el oscurecido de la capa.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.panel.setRenderEffect(RenderEffect.createBlurEffect(ZOOM_BLUR, ZOOM_BLUR, Shader.TileMode.CLAMP))
        }
        zoomed = true
        zoomBack.isEnabled = true
    }

    /** Reducir: el cuadro vuelve a la tarjeta, en su lugar y con sus 220 dp. Sin efecto si no estaba ampliado. */
    private fun closeZoom() {
        if (!zoomed) return
        zoomed = false
        zoomBack.isEnabled = false
        val stage = binding.otoscopeStage
        binding.otoscopeZoomBox.removeView(stage)
        binding.otoscopePlaceholder.visibility = View.GONE
        binding.otoscopeCard.addView(stage, stageIndex, stageParams)
        binding.otoscopeZoom.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) binding.panel.setRenderEffect(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** El YKUSH3 y la espera de las placas de audio van en el hilo del estetoscopio, nunca acá. */
    private fun listen() {
        binding.stethoMessage.setText(R.string.local_stetho_starting)
        runtime().localStethoStart { text -> runOnUiThread { if (unlocked) binding.stethoMessage.text = text } }
    }

    private fun showStethoGain() {
        binding.stethoGainButton.text = getString(R.string.local_stetho_gain, runtime().stetho.gain)
    }

    private fun startCamera() {
        binding.cameraMessage.text = ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        if (!unlocked) return
        if (cameraPaused) {
            binding.cameraMessage.setText(R.string.local_camera_paused)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            binding.cameraMessage.setText(R.string.local_camera_denied)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Pudo haberse bloqueado, o encendido el otoscopio, mientras la cámara arrancaba.
            if (!unlocked || cameraPaused) return@addListener
            val provider = try {
                future.get()
            } catch (_: Exception) {
                binding.cameraMessage.setText(R.string.local_camera_failed)
                return@addListener
            }
            cameraProvider = provider
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                if (!provider.hasCamera(selector)) {
                    binding.cameraMessage.setText(R.string.local_camera_missing)
                    return@addListener
                }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                provider.bindToLifecycle(this, selector, preview)
                binding.cameraMessage.text = ""
            } catch (_: Exception) {
                binding.cameraMessage.setText(R.string.local_camera_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Encender: la cámara de la tablet se suelta, el servicio prende el puerto
     * y espera al otoscopio en su hilo, y cuando aparece se abre por el camino
     * que corresponde. Ninguna orden al YKUSH3 sale de este hilo.
     */
    private fun otoscopeOn() {
        pauseTabletCamera()
        setOtoscopeMessage(getString(R.string.local_otoscope_starting))
        val runtime = runtime()
        runtime.localOtoscopeOn(
            progress = { text -> runOnUiThread { if (unlocked) setOtoscopeMessage(text) } },
            done = { path, text -> runOnUiThread { onOtoscopeFound(path, text) } },
        )
    }

    private fun onOtoscopeFound(path: OtoscopePower.Path?, text: String) {
        // Si el panel se bloqueó mientras esperaba, el servicio ya apagó el puerto.
        if (!unlocked) return
        setOtoscopeMessage(text)
        if (path == null) {
            resumeTabletCamera()
            return
        }
        val runtime = runtime()
        val source = when (path) {
            OtoscopePower.Path.CAMERA2 -> CameraXOtoscope(this, binding.otoscopePreview)
            OtoscopePower.Path.UVC ->
                UvcOtoscope(this, binding.otoscopeTexture, runtime.otoscopeProbe, runtime.otoscopeExecutor, runtime::log)
        }
        otoscope = source
        source.open { reason ->
            if (otoscope !== source || !unlocked) return@open
            if (reason == null) {
                setOtoscopeMessage(getString(R.string.local_otoscope_ready, source.label))
            } else {
                runtime.log("[OTOSCOPIO] No abrió ${source.label}: $reason")
                otoscopeOff(null)
                setOtoscopeMessage(reason)
            }
        }
    }

    /** Apagar: primero se suelta la cámara y recién después el servicio apaga el puerto. */
    private fun otoscopeOff(message: Int?) {
        releaseOtoscope()
        if (message != null) setOtoscopeMessage(getString(message))
        resumeTabletCamera()
    }

    private fun releaseOtoscope() {
        // Primero el cuadro vuelve a la tarjeta: sin cámara no hay nada que ampliar.
        closeZoom()
        otoscopeFreeze.unfreeze()
        val source = otoscope
        otoscope = null
        val runtime = runtime()
        if (source == null) {
            runtime.localOtoscopeOff()
        } else {
            source.close { runtime.localOtoscopeOff() }
        }
        cameraPaused = false
    }

    private fun pauseTabletCamera() {
        cameraPaused = true
        cameraFreeze.unfreeze()
        cameraProvider?.unbindAll()
        binding.cameraMessage.setText(R.string.local_camera_paused)
    }

    private fun resumeTabletCamera() {
        cameraPaused = false
        if (!unlocked) return
        binding.cameraMessage.text = ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
    }

    private fun render(s: LocalSnapshot) {
        binding.berryLine.text = PanelFormat.berryLine(s.berryUp, s.lastDataAge)

        val vitals = s.vitals
        binding.hrValue.text = PanelFormat.value(vitals.heartRate)
        binding.hrDetail.text = buildList {
            add(PanelFormat.electrodes(s.ecgInfo))
            if (s.ecgInfo.weakSignal == true) add("señal débil")
            PanelFormat.arrhythmia(s.ecgInfo.hrv)?.let(::add)
        }.joinToString(" · ")

        val (saturation, pulse) = PanelFormat.pair(vitals.spo2Pulse)
        binding.spo2Value.text = saturation
        binding.spo2Detail.text = "Pulso $pulse ${getString(R.string.local_unit_bpm)}"

        binding.respValue.text = PanelFormat.value(vitals.respRate)

        binding.tempValue.text = PanelFormat.value(vitals.temperature)
        binding.thermoDetail.text = PanelFormat.thermometer(s.thermoConnected, s.thermoReading, s.thermoAge)

        val pressure = PanelFormat.pressure(vitals.nibp)
        binding.nibpValue.text = pressure
        binding.nibpDetail.text = PanelFormat.nibpDetail(s.nibpRunning, s.nibp, pressure)
        binding.measureButton.isEnabled = s.berryUp && !s.nibpRunning
        binding.stopButton.isEnabled = s.nibpRunning

        binding.stethoCard.visibility = if (s.stethoAvailable) View.VISIBLE else View.GONE
        binding.stethoListenButton.isEnabled = !s.stethoRunning && !s.stethoStarting
        binding.stethoStopButton.isEnabled = s.stethoRunning || s.stethoStarting

        binding.otoscopeCard.visibility = if (s.otoscopeAvailable) View.VISIBLE else View.GONE
        val otoscopeIdle = s.otoscopeState == OtoscopePower.State.OFF && otoscope == null
        binding.otoscopePowerButton.setText(if (otoscopeIdle) R.string.local_otoscope_on else R.string.local_otoscope_off)
        binding.otoscopePowerButton.isEnabled = s.otoscopeState != OtoscopePower.State.POWERING_OFF
        val otoscopeLive = otoscope != null && s.otoscopeState == OtoscopePower.State.ON
        binding.otoscopeFreezeButton.isEnabled = otoscopeLive
        binding.otoscopeZoomButton.isEnabled = otoscopeLive
        binding.otoscopeZoomFreezeButton.isEnabled = otoscopeLive
        binding.otoscopeZoomPowerButton.isEnabled = s.otoscopeState != OtoscopePower.State.POWERING_OFF
    }

    companion object {
        private const val RENDER_MS = 500L
        private const val FAST_MM_S = 25f
        private const val SLOW_MM_S = 12.5f
        private const val SPO2_SAMPLES = 300
        private const val RESP_SAMPLES = 500
        private const val STETHO_SAMPLES = 1_000

        /** El ampliado deja 24 dp por lado y unos 80 dp abajo para los botones y el mensaje. */
        private const val ZOOM_MARGIN_DP = 24
        private const val ZOOM_BUTTONS_DP = 80
        private const val ZOOM_BLUR = 24f
    }
}
