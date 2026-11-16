// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.PinCheck
import com.guardiavirtual.berrymed.config.checkPin
import com.guardiavirtual.berrymed.databinding.ActivityHomeBinding
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.ui.status.PanelMode
import com.guardiavirtual.berrymed.ui.status.StatusPanelActions
import com.guardiavirtual.berrymed.ui.status.StatusPanelUi

/**
 * Inicio: el distribuidor de la tablet, con tres cards. Es la pantalla de
 * inicio de Android (HOME) y la del ícono de Berry Med, y adonde vuelve todo:
 * Finalizar y atrás del Panel Local, atrás de la configuración y la casita
 * del panel lateral.
 *
 * - ONDOCTOR365 abre el kiosco; sin la URL configurada, pide el PIN y abre la configuración.
 * - Panel Local abre el Panel Local, sin PIN.
 * - Configuración pide el PIN (el único de la app) y abre la pantalla técnica ([MainActivity]).
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val permissions = AppPermissionAsker(this)

    /** Qué se abre al aceptar el PIN: la configuración, con el formulario abierto o no. */
    private var afterPin: Intent? = null

    /** El panel lateral se refresca cada medio segundo mientras Inicio se ve. */
    private val statusTick = object : Runnable {
        override fun run() {
            renderStatus()
            binding.statusPanel.postDelayed(this, STATUS_MS)
        }
    }

    /** Atrás cierra el PIN; con el PIN cerrado no hace nada: Inicio es la pantalla de inicio. */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.pinLayer.visibility == View.VISIBLE) showPin(null)
        }
    }

    /** Toda la pantalla a la escala de diseño: ver [UiScale]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.scaled(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Los botones físicos mueven el volumen de medios, el mismo de la barra del panel lateral.
        volumeControlStream = AudioManager.STREAM_MUSIC
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.homeRoot.padForSystemBars()
        setUpStatusPanel()
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        // Cámara, micrófono, ubicación y notificaciones: todos juntos al abrir, como en el kiosco.
        permissions.askMissingOnOpen()
        onBackPressedDispatcher.addCallback(this, back)

        binding.homeOndoctor.setOnClickListener { openOndoctor() }
        binding.homeLocal.setOnClickListener { startActivity(Intent(this, LocalPanelActivity::class.java)) }
        binding.homeConfig.setOnClickListener { showPin(Intent(this, MainActivity::class.java)) }

        binding.pinLayer.setOnClickListener { showPin(null) }
        binding.pinBackButton.setOnClickListener { showPin(null) }
        binding.pinEnterButton.setOnClickListener { tryPin() }
        binding.pinInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                tryPin()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Abierto o en el riel, como se dejó en el kiosco o en el Panel Local.
        binding.statusPanel.setOpen(runtime().statusPanelOpen)
        binding.statusPanel.removeCallbacks(statusTick)
        statusTick.run()
    }

    /** A pantalla completa, como el kiosco y el Panel Local: las barras salen deslizando desde el borde. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        renderOndoctor()
    }

    override fun onStop() {
        // Al volver, Inicio está limpio: sin el PIN abierto ni a medio escribir.
        showPin(null)
        binding.statusPanel.removeCallbacks(statusTick)
        super.onStop()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    /** Wi-Fi, dispositivos y sonido, como en las otras pantallas; sin Modo ni casita: es Inicio. */
    private fun setUpStatusPanel() {
        val panel = binding.statusPanel
        panel.onToggle = { open -> runtime().statusPanelOpen = open }
        panel.onAudioChoice = { choice -> runtime().setCallAudioOutput(choice) }
        panel.onAction = { action -> StatusPanelActions.handle(this, runtime(), permissions, action) }
    }

    private fun renderStatus() {
        binding.statusPanel.render(StatusPanelUi.build(runtime().statusSnapshot(), PanelMode.HOME))
    }

    private fun kioskTarget(): HomeTarget {
        val runtime = runtime()
        return HomeTarget.of(runtime.configLoaded, runtime.config.kioskUrl)
    }

    /** Sin la URL del kiosco, la card lo dice. Con la configuración sin leer todavía, como siempre. */
    private fun renderOndoctor() {
        val missing = kioskTarget() == HomeTarget.SETTINGS
        binding.homeOndoctorBody.setText(if (missing) R.string.home_ondoctor_missing else R.string.home_ondoctor_body)
        binding.homeOndoctorBody.setTextColor(getColor(if (missing) R.color.warn else R.color.body))
    }

    private fun openOndoctor() {
        if (kioskTarget() == HomeTarget.SETTINGS) {
            showPin(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONFIG, true))
        } else {
            startActivity(Intent(this, KioskActivity::class.java))
        }
    }

    /** Abre el PIN para abrir [then] después, o lo cierra con null. */
    private fun showPin(then: Intent?) {
        afterPin = then
        binding.pinInput.setText("")
        binding.pinMessage.text = ""
        val open = then != null
        binding.pinLayer.visibility = if (open) View.VISIBLE else View.GONE
        val keyboard = getSystemService(InputMethodManager::class.java)
        if (open) {
            binding.pinInput.requestFocus()
            keyboard?.showSoftInput(binding.pinInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard?.hideSoftInputFromWindow(binding.pinInput.windowToken, 0)
        }
    }

    private fun tryPin() {
        val runtime = runtime()
        val wrong = when (checkPin(binding.pinInput.text?.toString(), runtime.config.localPanelPin, runtime.configLoaded)) {
            PinCheck.ACCEPTED -> null
            PinCheck.WRONG -> R.string.local_pin_wrong
            PinCheck.LOADING -> R.string.local_pin_loading
        }
        if (wrong != null) {
            binding.pinInput.setText("")
            binding.pinMessage.setText(wrong)
            return
        }
        val target = afterPin ?: return
        showPin(null)
        startActivity(target)
    }

    private companion object {
        const val STATUS_MS = 500L
    }
}
