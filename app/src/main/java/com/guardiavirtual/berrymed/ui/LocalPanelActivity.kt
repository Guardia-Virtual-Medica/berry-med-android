// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.pinAccepted
import com.guardiavirtual.berrymed.audio.StethoDsp
import com.guardiavirtual.berrymed.audio.StethoFilter
import com.guardiavirtual.berrymed.databinding.ActivityLocalPanelBinding
import com.guardiavirtual.berrymed.databinding.PanelRecordingRowBinding
import com.guardiavirtual.berrymed.otoscope.CameraXOtoscope
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.otoscope.OtoscopeSource
import com.guardiavirtual.berrymed.otoscope.UvcOtoscope
import com.guardiavirtual.berrymed.service.LocalSnapshot
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.ui.panel.CameraState
import com.guardiavirtual.berrymed.ui.panel.EcgLeads
import com.guardiavirtual.berrymed.ui.panel.FreezeFrame
import com.guardiavirtual.berrymed.ui.panel.Link
import com.guardiavirtual.berrymed.ui.panel.PanelDevice
import com.guardiavirtual.berrymed.ui.panel.PanelFormat
import com.guardiavirtual.berrymed.ui.panel.PanelState
import com.guardiavirtual.berrymed.ui.panel.PanelUi
import com.guardiavirtual.berrymed.ui.panel.PressureCard
import com.guardiavirtual.berrymed.ui.panel.RecordingRow
import com.guardiavirtual.berrymed.ui.panel.StethoPrimary
import com.guardiavirtual.berrymed.ui.panel.ScreenState
import com.guardiavirtual.berrymed.ui.panel.Segments
import com.guardiavirtual.berrymed.ui.panel.Status
import com.guardiavirtual.berrymed.ui.panel.TempCard
import com.guardiavirtual.berrymed.ui.panel.Tile
import com.guardiavirtual.berrymed.ui.panel.Tone
import kotlin.concurrent.thread

/**
 * Panel Local: atención presencial, con quien atiende al lado del paciente.
 * Muestra todos los instrumentos en vivo y no identifica, no guarda ni envía
 * nada. Mientras está abierto el servicio no manda POST ni atiende órdenes de
 * Pusher; al irse de la pantalla todo vuelve a la normalidad.
 *
 * Qué mostrar lo decide [PanelUi], sin Android; acá se aplica a las vistas y
 * se atienden los toques.
 */
class LocalPanelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalPanelBinding
    private val ui = PanelUi()
    private var unlocked = false
    private var lastRenderAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraFreeze: FreezeFrame
    private lateinit var otoscopeFreeze: FreezeFrame
    private lateinit var speeds: Segments
    private lateinit var gains: Segments
    private lateinit var leadSegments: Segments
    private lateinit var filterSegments: Segments
    private lateinit var recordingRows: List<PanelRecordingRowBinding>

    /** Las grabaciones que se ven en las filas, en el orden de las filas. */
    private var shownRecordings: List<RecordingRow> = emptyList()

    /** La imagen del otoscopio abierta en su panel, por Camera2 o por UVC. */
    private var otoscope: OtoscopeSource? = null

    /** La imagen ya abrió: recién ahí el estado dice "En vivo". */
    private var otoscopeOpened = false

    /** Lo último que dijo el otoscopio al encender, apagar o fallar; se ve en el visor mientras no hay imagen. */
    private var otoscopeText: CharSequence = ""

    /** Camera2 no garantiza dos cámaras abiertas a la vez: con el otoscopio encendido, la de la tablet espera. */
    private var cameraPaused = false
    private var cameraState = CameraState.STARTING

    /** El dispositivo abierto en el área principal; null es el ECG. */
    private var openDevice: PanelDevice? = null
    private var finishOpen = false

    /**
     * Atrás cierra el diálogo de Finalizar si está abierto y, si no, vuelve
     * al ECG desde un dispositivo. Con el ECG a la vista queda deshabilitado
     * y atrás hace lo de siempre: salir.
     */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (finishOpen) showFinish(false) else showDevice(null)
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!unlocked) return
            binding.ecgPaper.tick()
            if (openDevice != null) binding.stripPaper.tick()
            if (openDevice == PanelDevice.STETHOSCOPE) binding.stethoWave.tick()
            val now = SystemClock.uptimeMillis()
            if (now - lastRenderAt >= RENDER_MS) {
                lastRenderAt = now
                render(runtime().localSnapshot())
            }
            binding.ecgPaper.postOnAnimation(this)
        }
    }

    /** El reloj de la cabecera, al cambiar cada segundo. */
    private val clock = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            binding.clock.text = PanelFormat.clock(now)
            binding.date.text = PanelFormat.date(now)
            binding.clock.postDelayed(this, CLOCK_MS - now % CLOCK_MS)
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!unlocked) return@registerForActivityResult
        if (granted) bindCamera() else cameraUnavailable(R.string.local_camera_denied)
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
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) cameraState = CameraState.MISSING

        val live = runtime().live
        binding.ecgPaper.attach(live)
        binding.stripPaper.compact = true
        binding.stripPaper.attach(live)
        binding.stethoWave.attach(live.stetho)
        // Dos segundos a 500 por segundo: entran dos o tres latidos.
        binding.stethoWave.samplesPerScreen = STETHO_SAMPLES
        binding.stethoWave.traceColor = getColor(R.color.phono)
        binding.stethoWave.traceWidth = PHONO_STROKE_DP * resources.displayMetrics.density
        clipShapes()

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
        binding.finishButton.setOnClickListener { showFinish(true) }
        binding.finishCancel.setOnClickListener { showFinish(false) }
        // Lo mismo que hacía Salir: finish dispara stopLocal en onPause.
        binding.finishConfirm.setOnClickListener { finish() }
        // Tocar fuera de la tarjeta del diálogo lo cierra, como Cancelar.
        binding.finishLayer.setOnClickListener { showFinish(false) }
        onBackPressedDispatcher.addCallback(this, back)

        setUpEcgControls()
        setUpDock()

        binding.paButton.setOnClickListener {
            if (runtime().localSnapshot().nibpRunning) {
                nibp { runtime().localNibpStop() }
            } else {
                nibp { runtime().localNibpStart() }
            }
        }

        cameraFreeze = FreezeFrame(
            FreezeFrame.Views(
                image = binding.frozenImage,
                badge = binding.cameraBadge,
                toast = binding.cameraToast,
                primary = binding.freezeButton,
                help = binding.cameraHelp,
                own = binding.cameraSwitchButton,
                discard = binding.cameraDiscardButton,
                capturesLabel = binding.cameraCapturesLabel,
                capturesEmpty = binding.cameraCapturesEmpty,
                capturesGrid = binding.cameraCapturesGrid,
                thumbs = listOf(binding.cameraThumb0, binding.cameraThumb1, binding.cameraThumb2, binding.cameraThumb3)
                    .map { FreezeFrame.Thumb(it.root, it.thumbImage, it.thumbTime) },
            ),
            grab = { binding.cameraPreview.bitmap },
        )
        otoscopeFreeze = FreezeFrame(
            FreezeFrame.Views(
                image = binding.otoscopeFrozen,
                badge = binding.otoscopeBadge,
                toast = binding.otoscopeToast,
                primary = binding.otoscopeFreezeButton,
                help = binding.otoscopeHelp,
                own = binding.otoscopePowerButton,
                discard = binding.otoscopeDiscardButton,
                capturesLabel = binding.otoscopeCapturesLabel,
                capturesEmpty = binding.otoscopeCapturesEmpty,
                capturesGrid = binding.otoscopeCapturesGrid,
                thumbs = listOf(binding.otoscopeThumb0, binding.otoscopeThumb1, binding.otoscopeThumb2, binding.otoscopeThumb3)
                    .map { FreezeFrame.Thumb(it.root, it.thumbImage, it.thumbTime) },
            ),
            grab = { otoscope?.snapshot() },
        )
        binding.cameraSwitchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            cameraFreeze.unfreeze()
            bindCamera()
        }
        binding.otoscopePowerButton.setOnClickListener { otoscopePower() }

        setUpStethoscope()
        showDevice(null)
    }

    /** Cada vez que el panel vuelve al frente pide el PIN de nuevo. */
    override fun onStart() {
        super.onStart()
        hideSystemBars()
        lock()
        binding.clock.removeCallbacks(clock)
        clock.run()
    }

    /** Con el panel adelante, el servicio suma cámara y micrófono para la teleconsulta de después. */
    override fun onResume() {
        super.onResume()
        MonitorService.promote(this)
    }

    /** Al cerrar con Finalizar o el gesto de atrás el modo se corta ya: onStop puede tardar segundos. */
    override fun onPause() {
        if (isFinishing) {
            releaseOtoscope()
            runtime().stopLocal()
        }
        super.onPause()
    }

    override fun onStop() {
        binding.ecgPaper.removeCallbacks(frame)
        binding.clock.removeCallbacks(clock)
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

    /**
     * Las esquinas redondeadas del área principal y de los visores, y el
     * círculo del otoscopio: el video y el papel del ECG se recortan ahí.
     * El círculo mide 400 dp, o lo que entre en el visor.
     */
    private fun clipShapes() {
        binding.mainArea.clipToOutline = true
        binding.otoscopeViewer.clipToOutline = true
        binding.cameraViewer.clipToOutline = true
        val circle = binding.otoscopeCircle
        circle.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        circle.clipToOutline = true
        binding.otoscopeViewer.addOnLayoutChangeListener { viewer, _, _, _, _, _, _, _, _ ->
            val side = minOf(dp(OTOSCOPE_CIRCLE_DP), viewer.width - dp(VIEWER_MARGIN_DP), viewer.height - dp(VIEWER_MARGIN_DP))
                .coerceAtLeast(0)
            val params = circle.layoutParams
            if (params.width != side) {
                params.width = side
                params.height = side
                // Dentro de un layout no se pide otro: va en el próximo ciclo.
                circle.post { circle.layoutParams = params }
            }
        }
    }

    private fun setUpEcgControls() {
        val ecg = binding.ecgPaper
        leadSegments = Segments(listOf(binding.leadsII, binding.leads3, binding.leads7), initial = 2) { index ->
            ecg.leads = EcgLeads.entries[index]
        }
        speeds = Segments(listOf(binding.speed12, binding.speed25, binding.speed50), initial = 1) { index ->
            ecg.mmPerSecond = PanelUi.SPEEDS[index]
        }
        gains = Segments(listOf(binding.gain5, binding.gain10, binding.gain20), initial = 1) { index ->
            ecg.mmPerMv = PanelUi.GAINS[index]
        }
        binding.ecgFreezeButton.setOnClickListener { freezeEcg(!ecg.frozen) }
    }

    /** Congelar deja quietos el ECG y la tira; la plaqueta sigue mandando y la FC se sigue viendo. */
    private fun freezeEcg(frozen: Boolean) {
        binding.ecgPaper.frozen = frozen
        binding.stripPaper.frozen = frozen
        val button = binding.ecgFreezeButton
        if (frozen) {
            binding.ecgFrozenBadge.text = frozenLabel()
            binding.ecgFrozenBadge.visibility = View.VISIBLE
            val warn = ColorStateList.valueOf(getColor(R.color.warn))
            val dark = ColorStateList.valueOf(getColor(R.color.bg))
            button.backgroundTintList = warn
            button.strokeColor = warn
            button.setTextColor(dark)
            button.iconTint = dark
            button.setText(R.string.local_resume)
            button.setIconResource(R.drawable.ic_play_arrow)
        } else {
            binding.ecgFrozenBadge.visibility = View.GONE
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.panel_outline_bg)
            button.strokeColor = ColorStateList.valueOf(getColor(R.color.line3))
            val text = ContextCompat.getColorStateList(this, R.color.panel_button_text)
            button.setTextColor(text)
            button.iconTint = text
            button.setText(R.string.local_freeze)
            button.setIconResource(R.drawable.ic_pause)
        }
    }

    private fun frozenLabel(): String = getString(R.string.local_frozen_at, PanelFormat.clockSeconds(System.currentTimeMillis()))

    private fun setUpDock() {
        binding.otoscopeTile.setOnClickListener { toggleDevice(PanelDevice.OTOSCOPE) }
        binding.stethoTile.setOnClickListener { toggleDevice(PanelDevice.STETHOSCOPE) }
        binding.cameraTile.setOnClickListener { toggleDevice(PanelDevice.CAMERA) }
        binding.otoscopeBack.setOnClickListener { showDevice(null) }
        binding.stethoBack.setOnClickListener { showDevice(null) }
        binding.cameraBack.setOnClickListener { showDevice(null) }
        binding.viewEcgButton.setOnClickListener { showDevice(null) }
    }

    private fun toggleDevice(device: PanelDevice) = showDevice(if (openDevice == device) null else device)

    /**
     * Abre un dispositivo en el área principal, o el ECG con null. Abrir no
     * enciende nada y cerrar no apaga nada: el otoscopio sigue transmitiendo
     * aunque se vuelva al ECG.
     *
     * Los cuatro paneles quedan siempre visibles, uno encima del otro, y el
     * abierto pasa adelante. Con visibility GONE el TextureView del video deja
     * de consumir cuadros y la cámara (o el otoscopio por UVC) podría trabarse
     * esperando lugar; adelante o atrás, siempre se dibujan.
     */
    private fun showDevice(device: PanelDevice?) {
        val changed = openDevice != device
        openDevice = device
        if (changed) {
            // Al cambiar de dispositivo el video vuelve al vivo.
            cameraFreeze.unfreeze()
            otoscopeFreeze.unfreeze()
        }
        val front = when (device) {
            null -> binding.ecgPanel
            PanelDevice.OTOSCOPE -> binding.otoscopePanel
            PanelDevice.STETHOSCOPE -> binding.stethoPanel
            PanelDevice.CAMERA -> binding.cameraPanel
        }
        for (panel in listOf(binding.ecgPanel, binding.otoscopePanel, binding.stethoPanel, binding.cameraPanel)) {
            val shown = panel === front
            // Los de atrás no reciben toques ni los lee TalkBack.
            panel.isClickable = true
            panel.importantForAccessibility = if (shown) {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            panel.visibility = if (shown || panel !== binding.ecgPanel) View.VISIBLE else View.INVISIBLE
        }
        front.bringToFront()
        binding.ecgStrip.visibility = if (device == null) View.GONE else View.VISIBLE
        binding.otoscopeTile.isActivated = device == PanelDevice.OTOSCOPE
        binding.stethoTile.isActivated = device == PanelDevice.STETHOSCOPE
        binding.cameraTile.isActivated = device == PanelDevice.CAMERA
        if (changed && device != null) binding.stripPaper.restart()
        updateBack()
        if (unlocked) render(runtime().localSnapshot())
    }

    private fun showFinish(open: Boolean) {
        finishOpen = open
        binding.finishLayer.visibility = if (open) View.VISIBLE else View.GONE
        updateBack()
    }

    private fun updateBack() {
        back.isEnabled = finishOpen || openDevice != null
    }

    private fun lock() {
        unlocked = false
        showFinish(false)
        // Al desbloquear vuelve el ECG a la vista; los dispositivos quedan como estén en el runtime.
        showDevice(null)
        freezeEcg(false)
        binding.lock.visibility = View.VISIBLE
        binding.pinInput.setText("")
        binding.lockMessage.text = ""
        // Las capturas se descartan al bloquearse, y Finalizar pasa por acá (onStop): no se guardan en ningún lado.
        cameraFreeze.clear()
        otoscopeFreeze.clear()
        // Las grabaciones del estetoscopio, igual: se cortan la reproducción y la grabación en curso.
        runtime().localStethoDiscard()
        shownRecordings = emptyList()
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
        // Al cerrarse el teclado del PIN Android puede devolver las barras del sistema.
        hideSystemBars()
        // startLocal vació las ondas: las pantallas arrancan en blanco y leen desde cero.
        binding.ecgPaper.restart()
        binding.stripPaper.restart()
        binding.stethoWave.restart()
        binding.stethoMessage.text = ""
        setOtoscopeMessage("")
        unlocked = true
        lastRenderAt = 0L
        binding.ecgPaper.postOnAnimation(frame)
        startCamera()
    }

    /** Medir y Cancelar escriben en el puerto serie: fuera del hilo de la pantalla. */
    private fun nibp(action: () -> String) {
        thread(name = "berry-local-nibp") {
            val text = action()
            // Lo que sale bien ya se ve en la tarjeta; sólo se avisa lo que no.
            if (text.startsWith("No se pudo") || text.startsWith("El panel")) {
                runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun setUpStethoscope() {
        val stetho = runtime().stetho
        binding.stethoListenButton.setOnClickListener { stethoPrimary() }
        binding.stethoStopButton.setOnClickListener {
            runtime().localStethoStop { text -> runOnUiThread { stethoMessage(text) } }
        }
        // El filtro se aplica en el hilo de audio desde el próximo bloque, sin cortar la escucha.
        filterSegments = Segments(
            listOf(binding.filterHeart, binding.filterLung, binding.filterWide),
            initial = stetho.filter.ordinal,
        ) { index ->
            stetho.filter = StethoFilter.entries[index]
            showPhonoLabel(stetho.filter)
        }
        showPhonoLabel(stetho.filter)
        binding.volumeDown.setOnClickListener { stepVolume(up = false) }
        binding.volumeUp.setOnClickListener { stepVolume(up = true) }
        showVolume()
        recordingRows = listOf(binding.recording0, binding.recording1, binding.recording2)
        recordingRows.forEachIndexed { index, row -> row.root.setOnClickListener { toggleRecording(index) } }
    }

    /** Escuchar, Grabar 15 s o, mientras graba, cortar y guardar lo que lleva. */
    private fun stethoPrimary() {
        val runtime = runtime()
        when (PanelUi.stethoButtons(runtime.localSnapshot()).primary) {
            StethoPrimary.LISTEN ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    listen()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            StethoPrimary.RECORD -> stethoMessage(runtime.localStethoRecord())
            StethoPrimary.RECORDING -> stethoMessage(runtime.localStethoStopRecording())
        }
        render(runtime.localSnapshot())
    }

    /**
     * Tocar una grabación la reproduce por el auricular; tocar la que suena
     * la para. Si se está escuchando, el runtime para primero la escucha.
     */
    private fun toggleRecording(slot: Int) {
        val row = shownRecordings.getOrNull(slot) ?: return
        val runtime = runtime()
        if (row.playing) {
            runtime.localStethoStopPlayback { runOnUiThread { stethoMessage("") } }
        } else {
            runtime.localStethoPlay(row.number) { text -> runOnUiThread { stethoMessage(text) } }
        }
    }

    private fun stethoMessage(text: CharSequence) {
        if (unlocked) binding.stethoMessage.text = text
    }

    private fun filterName(filter: StethoFilter): String = getString(
        when (filter) {
            StethoFilter.HEART -> R.string.local_stetho_heart
            StethoFilter.LUNG -> R.string.local_stetho_lung
            StethoFilter.WIDE -> R.string.local_stetho_wide
        },
    )

    private fun showPhonoLabel(filter: StethoFilter) {
        binding.phonoLabel.text = getString(R.string.local_phono_label, filterName(filter))
    }

    private fun stepVolume(up: Boolean) {
        val stetho = runtime().stetho
        stetho.volume = PanelUi.stethoVolumeStep(stetho.volume, up)
        showVolume()
    }

    private fun showVolume() {
        val volume = runtime().stetho.volume
        binding.volumeValue.text = volume.toString()
        binding.volumeDown.isEnabled = volume > StethoDsp.VOLUME_MIN
        binding.volumeUp.isEnabled = volume < StethoDsp.VOLUME_MAX
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

    private fun setOtoscopeMessage(text: CharSequence) {
        otoscopeText = text
        binding.otoscopeMessage.text = text
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** El YKUSH3 y la espera de las placas de audio van en el hilo del estetoscopio, nunca acá. */
    private fun listen() {
        binding.stethoMessage.setText(R.string.local_stetho_starting)
        runtime().localStethoStart { text -> runOnUiThread { if (unlocked) binding.stethoMessage.text = text } }
    }

    private fun startCamera() {
        if (cameraState == CameraState.MISSING) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun cameraUnavailable(message: Int) {
        cameraState = CameraState.UNAVAILABLE
        binding.cameraMessage.setText(message)
    }

    private fun bindCamera() {
        if (!unlocked || cameraState == CameraState.MISSING) return
        if (cameraPaused) {
            cameraState = CameraState.PAUSED
            binding.cameraMessage.setText(R.string.local_camera_paused)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraUnavailable(R.string.local_camera_denied)
            return
        }
        cameraState = CameraState.STARTING
        binding.cameraMessage.text = ""
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Pudo haberse bloqueado, o encendido el otoscopio, mientras la cámara arrancaba.
            if (!unlocked || cameraPaused) return@addListener
            val provider = try {
                future.get()
            } catch (_: Exception) {
                cameraUnavailable(R.string.local_camera_failed)
                return@addListener
            }
            cameraProvider = provider
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                if (!provider.hasCamera(selector)) {
                    cameraUnavailable(R.string.local_camera_missing)
                    return@addListener
                }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                provider.bindToLifecycle(this, selector, preview)
                cameraState = CameraState.LIVE
                binding.cameraMessage.text = ""
            } catch (_: Exception) {
                cameraUnavailable(R.string.local_camera_failed)
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
                otoscopeOpened = true
                setOtoscopeMessage(getString(R.string.local_otoscope_ready, source.label))
            } else {
                runtime.log("[OTOSCOPIO] No abrió ${source.label}: $reason")
                otoscopeOff(null)
                setOtoscopeMessage(reason)
            }
            render(runtime.localSnapshot())
        }
    }

    /** Apagar: primero se suelta la cámara y recién después el servicio apaga el puerto. */
    private fun otoscopeOff(message: Int?) {
        releaseOtoscope()
        if (message != null) setOtoscopeMessage(getString(message))
        resumeTabletCamera()
    }

    private fun releaseOtoscope() {
        otoscopeFreeze.unfreeze()
        val source = otoscope
        otoscope = null
        otoscopeOpened = false
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
        if (cameraState != CameraState.MISSING) {
            cameraState = CameraState.PAUSED
            binding.cameraMessage.setText(R.string.local_camera_paused)
        }
    }

    private fun resumeTabletCamera() {
        cameraPaused = false
        if (!unlocked || cameraState == CameraState.MISSING) return
        binding.cameraMessage.text = ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            cameraUnavailable(R.string.local_camera_denied)
        }
    }

    private fun screenState() = ScreenState(
        otoscopeLabel = otoscope?.label?.takeIf { otoscopeOpened },
        camera = cameraState,
        cameraFront = lensFacing == CameraSelector.LENS_FACING_FRONT,
        openDevice = openDevice,
    )

    private fun render(s: LocalSnapshot) {
        val state = ui.build(s, System.currentTimeMillis(), screenState())
        renderHeader(state)
        renderVitals(state, s)
        renderDock(state)
        renderDevices(state, s)
    }

    private fun renderHeader(state: PanelState) {
        val chips = state.chips
        chip(binding.chipEcg, chips.ecg)
        chip(binding.chipSpo2, chips.spo2)
        chip(binding.chipPa, chips.pressure)
        chip(binding.chipTemp, chips.temperature)
        chip(binding.chipOtoscope, chips.otoscope)
        chip(binding.chipStetho, chips.stethoscope)
        status(binding.ecgSignal, state.signal)
    }

    private fun chip(view: TextView, link: Link) {
        val dot = when (link) {
            Link.CONNECTED -> R.color.ok
            Link.CONNECTING -> R.color.warn
            Link.DISCONNECTED -> R.color.line3
        }
        view.setBackgroundResource(if (link == Link.DISCONNECTED) R.drawable.panel_chip_off else R.drawable.panel_chip_on)
        view.setTextColor(getColor(if (link == Link.DISCONNECTED) R.color.faint else R.color.text2))
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(getColor(dot)))
    }

    /** Un texto de estado con su punto, los dos del color del tono. */
    private fun status(view: TextView, status: Status) {
        val color = getColor(toneColor(status.tone))
        view.text = status.text
        view.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color))
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.OK -> R.color.ok
        Tone.WARN -> R.color.warn
        Tone.MUTED -> R.color.muted
        Tone.FAINT -> R.color.faint
        Tone.RECORDING -> R.color.recording
    }

    private fun renderVitals(state: PanelState, s: LocalSnapshot) {
        binding.hrValue.text = state.heartRate
        binding.hrNote.text = state.heartNote
        binding.stripHr.text = getString(R.string.local_strip_hr, state.heartRate)
        binding.spo2Value.text = state.saturation
        binding.pulseValue.text = state.pulse
        binding.respValue.text = state.respiration

        val pressure = state.pressure
        binding.paResult.visibility = if (pressure is PressureCard.Result || pressure == PressureCard.Empty) View.VISIBLE else View.GONE
        binding.paMeasuring.visibility = if (pressure is PressureCard.Measuring) View.VISIBLE else View.GONE
        binding.paNoResult.visibility = if (pressure == PressureCard.NoResult) View.VISIBLE else View.GONE
        when (pressure) {
            is PressureCard.Result -> {
                binding.paValue.text = pressure.value
                binding.paMean.text = pressure.mean
                binding.paTime.text = pressure.at
            }
            is PressureCard.Measuring -> {
                binding.paCuff.text = pressure.cuff
                binding.paPhase.text = pressure.phase
                binding.paProgress.progress = (pressure.progress * binding.paProgress.max).toInt()
                binding.paTime.setText(R.string.local_pa_measuring)
            }
            PressureCard.Empty -> {
                binding.paValue.text = PanelFormat.NONE
                binding.paMean.text = ""
                binding.paTime.text = ""
            }
            PressureCard.NoResult -> binding.paTime.text = ""
        }
        binding.paButton.setText(if (s.nibpRunning) R.string.local_cancel else R.string.local_measure)
        binding.paButton.isEnabled = s.nibpRunning || state.canMeasure

        val temperature = state.temperature
        val disconnected = temperature == TempCard.Disconnected
        binding.tempCard.setBackgroundResource(if (disconnected) R.drawable.panel_card_dashed else R.drawable.panel_card)
        binding.tempLabel.setTextColor(getColor(if (disconnected) R.color.faint else R.color.temp))
        binding.tempUnit.setTextColor(getColor(if (disconnected) R.color.faint else R.color.muted))
        binding.tempValue.visibility = if (temperature is TempCard.Reading) View.VISIBLE else View.GONE
        binding.tempMessage.visibility = if (temperature is TempCard.Reading) View.GONE else View.VISIBLE
        when (temperature) {
            TempCard.Disconnected -> {
                binding.tempMessage.setText(R.string.local_thermo_off)
                binding.tempTime.text = ""
            }
            TempCard.Waiting -> {
                binding.tempMessage.setText(R.string.local_thermo_wait)
                binding.tempTime.text = ""
            }
            is TempCard.Reading -> {
                binding.tempValue.text = temperature.value
                binding.tempTime.text = temperature.at
            }
        }
    }

    private fun renderDock(state: PanelState) {
        tile(binding.otoscopeTile, binding.otoscopeTileStatus, binding.otoscopeTileIcon, state.otoscopeTile, PanelDevice.OTOSCOPE)
        tile(binding.stethoTile, binding.stethoTileStatus, binding.stethoTileIcon, state.stethoscopeTile, PanelDevice.STETHOSCOPE)
        tile(binding.cameraTile, binding.cameraTileStatus, binding.cameraTileIcon, state.cameraTile, PanelDevice.CAMERA)
    }

    /** Sin el equipo el tile dice "desconectado", no abre nada y no tiene cheurón; abierto, muestra la cruz. */
    private fun tile(view: View, statusView: TextView, icon: ImageView, tile: Tile, device: PanelDevice) {
        val active = openDevice == device
        view.isEnabled = tile.enabled
        view.isClickable = tile.enabled
        statusView.text = tile.status.text
        statusView.setTextColor(getColor(toneColor(tile.status.tone)))
        icon.visibility = if (tile.enabled) View.VISIBLE else View.INVISIBLE
        icon.setImageResource(if (active) R.drawable.ic_close else R.drawable.ic_chevron_right)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(getColor(if (active) R.color.muted else R.color.faint)))
    }

    private fun renderDevices(state: PanelState, s: LocalSnapshot) {
        status(binding.otoscopeStatus, state.otoscopeStatus)
        status(binding.stethoStatus, state.stethoscopeStatus)
        status(binding.cameraStatus, state.cameraStatus)

        val otoscopeIdle = s.otoscopeState == OtoscopePower.State.OFF && otoscope == null
        binding.otoscopePowerButton.setText(if (otoscopeIdle) R.string.local_otoscope_on else R.string.local_otoscope_off)
        binding.otoscopePowerButton.isEnabled = s.otoscopeState != OtoscopePower.State.POWERING_OFF &&
            (s.otoscopeAvailable || !otoscopeIdle)
        val otoscopeLive = otoscopeOpened && s.otoscopeState == OtoscopePower.State.ON
        otoscopeFreeze.setCanFreeze(otoscopeLive)
        // Mientras hay imagen el mensaje se va: lo dice el estado del encabezado.
        binding.otoscopeMessage.visibility = if (otoscopeLive) View.GONE else View.VISIBLE
        if (otoscopeIdle && otoscopeText.isEmpty()) {
            binding.otoscopeMessage.setText(if (s.otoscopeAvailable) R.string.local_otoscope_idle else R.string.local_otoscope_missing)
        } else {
            binding.otoscopeMessage.text = otoscopeText
        }

        renderStethoscope(state)

        cameraFreeze.setCanFreeze(cameraState == CameraState.LIVE)
        binding.cameraSwitchButton.isEnabled = cameraState != CameraState.MISSING && !cameraPaused
        binding.cameraMessage.visibility = if (cameraState == CameraState.LIVE) View.GONE else View.VISIBLE
    }

    private fun renderStethoscope(state: PanelState) {
        val stetho = state.stetho
        val primary = binding.stethoListenButton
        primary.isEnabled = stetho.primaryEnabled
        if (stetho.primary == StethoPrimary.RECORDING) {
            val white = ColorStateList.valueOf(Color.WHITE)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.alarm))
            primary.setTextColor(white)
            primary.iconTint = white
            primary.text = getString(R.string.local_stetho_recording, stetho.remaining)
            primary.setIconResource(R.drawable.ic_fiber_manual_record)
        } else {
            val text = ContextCompat.getColorStateList(this, R.color.panel_primary_text)
            primary.backgroundTintList = ContextCompat.getColorStateList(this, R.color.panel_primary_bg)
            primary.setTextColor(text)
            primary.iconTint = text
            val listen = stetho.primary == StethoPrimary.LISTEN
            primary.setText(if (listen) R.string.local_stetho_listen else R.string.local_stetho_record)
            primary.setIconResource(if (listen) R.drawable.ic_hearing else R.drawable.ic_mic)
        }
        binding.stethoStopButton.isEnabled = stetho.stopEnabled

        filterSegments.select(state.stethoFilter.ordinal)
        filterSegments.setEnabled(stetho.filters)

        shownRecordings = state.recordings
        binding.recordingsEmpty.visibility = if (shownRecordings.isEmpty()) View.VISIBLE else View.GONE
        binding.recordingsRow.visibility = if (shownRecordings.isEmpty()) View.GONE else View.VISIBLE
        recordingRows.forEachIndexed { index, row ->
            val recording = shownRecordings.getOrNull(index)
            if (recording == null) {
                row.root.visibility = View.INVISIBLE
                return@forEachIndexed
            }
            row.root.visibility = View.VISIBLE
            row.recordingLabel.text = getString(R.string.local_recording_row, recording.number, filterName(recording.filter))
            row.recordingTime.text = recording.at
            row.recordingIcon.setImageResource(if (recording.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            row.root.contentDescription = getString(
                if (recording.playing) R.string.local_recording_pause else R.string.local_recording_play,
                recording.number,
            )
        }
    }

    companion object {
        private const val RENDER_MS = 500L
        private const val CLOCK_MS = 1_000L
        private const val STETHO_SAMPLES = 1_000
        private const val PHONO_STROKE_DP = 1.2f
        private const val OTOSCOPE_CIRCLE_DP = 400
        private const val VIEWER_MARGIN_DP = 24
    }
}
