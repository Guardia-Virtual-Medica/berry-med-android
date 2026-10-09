// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.buildMetricsUrl
import com.guardiavirtual.berrymed.config.hasWebScheme
import com.guardiavirtual.berrymed.databinding.ActivityMainBinding
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime
import com.guardiavirtual.berrymed.service.TotemState
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var step = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) render(runtime().state.value)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showStep(0)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = preview()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.apiUrl.addTextChangedListener(watcher)
        binding.totemId.addTextChangedListener(watcher)
        binding.nextButton.setOnClickListener { advance() }
        binding.backButton.setOnClickListener {
            clearMessage()
            showStep(step - 1)
        }
        listOf(binding.stepDestination, binding.stepOrders, binding.stepDevice).forEachIndexed { index, label ->
            label.setOnClickListener {
                clearMessage()
                showStep(index)
            }
        }
        binding.saveButton.setOnClickListener { save() }
        binding.batteryButton.setOnClickListener { askBatteryExemption() }
        binding.usbButton.setOnClickListener { runtime().requestUsbPermissionAgain() }
        thread(name = "berry-config-load") {
            val saved = try {
                runBlocking { runtime().store.load() }
            } catch (error: Exception) {
                Log.e(TotemRuntime.TAG, "[CONFIG] No se pudo leer la configuración: $error")
                null
            }
            handler.post {
                fill(saved ?: BerryConfig())
                if (saved == null) {
                    binding.formMessage.setTextColor(getColor(R.color.warn))
                    binding.formMessage.setText(R.string.load_failed)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    private fun fill(config: BerryConfig) {
        binding.apiUrl.setText(config.apiBaseUrl)
        binding.totemId.setText(config.totemId)
        binding.apiUser.setText(config.apiUsername)
        binding.apiPassword.setText(config.apiPassword)
        binding.pusherKey.setText(config.pusherKey)
        binding.pusherCluster.setText(config.pusherCluster)
        binding.channel.setText(config.publicChannel)
        binding.startEvent.setText(config.startEventName)
        binding.stopEvent.setText(config.stopEventName)
        binding.maxMinutes.setText(config.maxSessionMinutes.toString())
        binding.nibpTarget.setText(config.nibpTargetPressureMmhg)
        select(binding.ecgGain, GAIN_VALUES, config.ecgGain)
        select(binding.ecgMode, MODE_VALUES, config.ecgMode)
        select(binding.respGain, GAIN_VALUES, config.respGain)
        select(binding.nibpMode, NIBP_VALUES, config.nibpMode)
        preview()
    }

    private fun advance() {
        val missing = missingField(step)
        if (missing != null) {
            binding.formMessage.setText(missing)
            binding.formMessage.setTextColor(getColor(R.color.warn))
            return
        }
        clearMessage()
        showStep(step + 1)
    }

    private fun showStep(index: Int) {
        step = index.coerceIn(0, 2)
        binding.steps.displayedChild = step
        binding.backButton.visibility = if (step == 0) View.GONE else View.VISIBLE
        binding.nextButton.visibility = if (step == 2) View.GONE else View.VISIBLE
        binding.saveButton.visibility = if (step == 2) View.VISIBLE else View.GONE
        val labels = listOf(binding.stepDestination, binding.stepOrders, binding.stepDevice)
        labels.forEachIndexed { i, label ->
            val current = i == step
            label.setTextColor(getColor(if (current) R.color.signal else R.color.ink_soft))
            label.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun missingField(index: Int): Int? = when (index) {
        0 -> when {
            binding.apiUrl.text.isNullOrBlank() -> R.string.missing_api
            !hasWebScheme(binding.apiUrl.text.toString()) -> R.string.missing_scheme
            binding.totemId.text.isNullOrBlank() -> R.string.missing_totem
            binding.apiUser.text.isNullOrBlank() -> R.string.missing_user
            binding.apiPassword.text.isNullOrBlank() -> R.string.missing_password
            else -> null
        }
        1 -> when {
            binding.pusherKey.text.isNullOrBlank() -> R.string.missing_pusher
            binding.channel.text.isNullOrBlank() -> R.string.missing_channel
            else -> null
        }
        else -> null
    }

    private fun clearMessage() {
        binding.formMessage.text = ""
    }

    private fun save() {
        val blocked = (0..1).firstNotNullOfOrNull { index ->
            missingField(index)?.let { index to it }
        }
        if (blocked != null) {
            showStep(blocked.first)
            binding.formMessage.setText(blocked.second)
            binding.formMessage.setTextColor(getColor(R.color.warn))
            return
        }
        val config = readForm()
        binding.formMessage.setTextColor(getColor(R.color.ink))
        binding.formMessage.setText(R.string.saving)
        thread(name = "berry-config-save") {
            val saved = try {
                runBlocking { runtime().store.save(config) }
                true
            } catch (error: Exception) {
                Log.e(TotemRuntime.TAG, "[CONFIG] No se pudo guardar la configuración: $error")
                false
            }
            handler.post {
                binding.formMessage.setTextColor(getColor(if (saved) R.color.signal else R.color.warn))
                binding.formMessage.setText(if (saved) R.string.saved else R.string.save_failed)
            }
        }
    }

    private fun readForm(): BerryConfig = BerryConfig(
        apiBaseUrl = binding.apiUrl.text?.toString()?.trim().orEmpty(),
        totemId = binding.totemId.text?.toString()?.trim().orEmpty(),
        apiUsername = binding.apiUser.text?.toString()?.trim().orEmpty(),
        apiPassword = binding.apiPassword.text?.toString()?.trim().orEmpty(),
        pusherKey = binding.pusherKey.text?.toString()?.trim().orEmpty(),
        pusherCluster = binding.pusherCluster.text?.toString()?.trim().orEmpty().ifBlank { "us2" },
        publicChannel = binding.channel.text?.toString()?.trim().orEmpty(),
        startEventName = binding.startEvent.text?.toString()?.trim().orEmpty().ifBlank { "start-monitoring" },
        stopEventName = binding.stopEvent.text?.toString()?.trim().orEmpty().ifBlank { "stop-monitoring" },
        ecgGain = value(binding.ecgGain, GAIN_VALUES),
        ecgMode = value(binding.ecgMode, MODE_VALUES),
        respGain = value(binding.respGain, GAIN_VALUES),
        nibpMode = value(binding.nibpMode, NIBP_VALUES),
        nibpTargetPressureMmhg = binding.nibpTarget.text?.toString()?.trim().orEmpty(),
        maxSessionMinutes = binding.maxMinutes.text?.toString()?.trim()?.toIntOrNull() ?: 30,
    )

    private fun preview() {
        val base = binding.apiUrl.text?.toString()?.trim().orEmpty()
        val totem = binding.totemId.text?.toString()?.trim().orEmpty()
        binding.urlPreview.text = if (base.isBlank() || totem.isBlank()) {
            getString(R.string.url_preview_empty)
        } else {
            buildMetricsUrl(base, totem)
        }
    }

    private fun render(state: TotemState) {
        paint(binding.heartRate, state.heartRate)
        paint(binding.spo2Pulse, state.spo2Pulse)
        paint(binding.respRate, state.respRate)
        paint(binding.temperature, state.temperature)
        paint(binding.nibp, state.nibp)
        binding.berryStatus.text = if (state.berryUp) "Plaqueta funcional" else "Plaqueta sin conectar"
        binding.berryDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (state.berryUp) R.color.status_live else R.color.vital_dim),
        )
        binding.lastData.text = state.lastData
        binding.pusherStatus.text = if (state.pusherUp) {
            "Comunicación con Pusher: OK"
        } else {
            "Comunicación con Pusher: sin conexión"
        }
        binding.otherStatus.text = state.others
        binding.lastPost.text = state.lastPost
    }

    private fun paint(view: TextView, value: String) {
        view.text = value
        val empty = value == "—" || value == "-" || value == "- -" || value == "- - /- -"
        view.setTextColor(getColor(if (empty) R.color.vital_dim else R.color.vital_on))
    }

    /** Algunas tablets no tienen el diálogo directo: se va a la lista general, y si tampoco está, se avisa. */
    private fun askBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(direct, list)) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        binding.formMessage.setTextColor(getColor(R.color.warn))
        binding.formMessage.setText(R.string.battery_unavailable)
    }

    private fun select(group: ChipGroup, values: Array<String>, selected: String) {
        val index = values.indexOf(selected).coerceAtLeast(0)
        (group.getChildAt(index) as? Chip)?.isChecked = true
    }

    private fun value(group: ChipGroup, values: Array<String>): String {
        val checked = group.checkedChipId
        val index = (0 until group.childCount).indexOfFirst { group.getChildAt(it).id == checked }
        return values.getOrElse(index) { values.first() }
    }

    companion object {
        private val GAIN_VALUES = arrayOf("x0.25", "x0.5", "x1", "x2")
        private val MODE_VALUES = arrayOf("operacion", "monitor", "diagnostico")
        private val NIBP_VALUES = arrayOf("adulto", "nino", "neonato")
    }
}
