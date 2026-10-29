// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.guardiavirtual.berrymed.config.DEFAULT_HEADSET
import com.guardiavirtual.berrymed.config.DEFAULT_LOCAL_PIN
import com.guardiavirtual.berrymed.config.DEFAULT_STETHO_INPUT
import com.guardiavirtual.berrymed.config.Environment
import com.guardiavirtual.berrymed.config.buildMetricsUrl
import com.guardiavirtual.berrymed.config.defaultChannel
import com.guardiavirtual.berrymed.config.hasWebScheme
import com.guardiavirtual.berrymed.config.kioskUrlAccepted
import com.guardiavirtual.berrymed.config.ykushPortOrDefault
import com.guardiavirtual.berrymed.databinding.ActivityMainBinding
import com.guardiavirtual.berrymed.databinding.ItemKioskCheckBinding
import com.guardiavirtual.berrymed.service.ForegroundAccess
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime
import com.guardiavirtual.berrymed.service.TotemState
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var step = 0
    private var filling = false
    /** La URL del kiosco guardada: "Abrir el kiosco" sólo con la del campo ya guardada. */
    private var savedKioskUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) {
                render(runtime().state.value)
                // Los tipos del servicio cambian un rato después de promote: el paso los sigue.
                if (step == STEP_KIOSK) renderChecklist()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        askPermissions()
        showStep(0)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = preview()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.apiUrl.addTextChangedListener(watcher)
        binding.totemId.addTextChangedListener(watcher)
        binding.totemId.addTextChangedListener(ChannelFollower())
        // "Abrir el kiosco" se habilita sólo con la URL del campo ya guardada.
        binding.kioskUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderChecklist()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.environment.setOnCheckedStateChangeListener { _, _ ->
            if (!filling) applyEnvironment()
        }
        binding.nextButton.setOnClickListener { advance() }
        binding.backButton.setOnClickListener {
            clearMessage()
            showStep(step - 1)
        }
        stepLabels().forEachIndexed { index, label ->
            label.setOnClickListener {
                clearMessage()
                showStep(index)
            }
        }
        binding.saveButton.setOnClickListener { save() }
        binding.batteryButton.setOnClickListener { askBatteryExemption() }
        binding.usbButton.setOnClickListener { runtime().requestUsbPermissionAgain() }
        binding.testingButton.setOnClickListener { startActivity(Intent(this, TestingActivity::class.java)) }
        binding.localPanelButton.setOnClickListener { startActivity(Intent(this, LocalPanelActivity::class.java)) }
        binding.checkPermissions.action.setOnClickListener { askMediaPermissions() }
        binding.checkHome.action.setOnClickListener { openHomeSettings() }
        // El mismo pedido que el botón "Evitar que se duerma" de arriba.
        binding.checkBattery.action.setOnClickListener { askBatteryExemption() }
        binding.checkTotem.action.setOnClickListener { startActivity(Intent(this, KioskActivity::class.java)) }
        thread(name = "berry-config-load") {
            val saved = try {
                runBlocking { runtime().store.load() }
            } catch (error: Exception) {
                Log.e(TotemRuntime.TAG, "[CONFIG] No se pudo leer la configuración: $error")
                null
            }
            handler.post {
                savedKioskUrl = saved?.kioskUrl?.trim().orEmpty()
                fill(saved ?: BerryConfig())
                if (saved == null) {
                    binding.formMessage.setTextColor(getColor(R.color.form_error))
                    binding.formMessage.setText(R.string.load_failed)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    /** Con la pantalla adelante el servicio suma cámara y micrófono para la teleconsulta. */
    override fun onResume() {
        super.onResume()
        MonitorService.promote(this)
        // Al volver de Ajustes (app de inicio, batería, permisos) la lista muestra lo nuevo.
        renderChecklist()
    }

    /**
     * Las notificaciones, y la cámara y el micrófono para el otoscopio y el
     * estetoscopio de la teleconsulta. Sólo los que faltan.
     */
    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) ActivityCompat.requestPermissions(this, wanted.toTypedArray(), REQUEST_STARTUP)
    }

    /** "Dar permisos" del paso Kiosco: cámara y micrófono, los que falten. */
    private fun askMediaPermissions() {
        val missing = MEDIA_PERMISSIONS.filterNot(::granted)
        if (missing.isEmpty()) return renderChecklist()
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_MEDIA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Recién otorgados: el servicio los suma ahora, con la pantalla todavía adelante.
        MonitorService.promote(this)
        renderChecklist()
        if (requestCode != REQUEST_MEDIA || grantResults.isEmpty()) return
        // Negado y sin explicación pendiente: Android ya no muestra el diálogo
        // (se negó dos veces o "No volver a preguntar"). Sólo quedan los ajustes de la app.
        val blocked = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])
        }
        if (blocked) openAppSettings()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** La lista de apps de inicio; algunas tablets no la tienen y queda Ajustes a secas. */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** La app que abre el botón de inicio. Con otra elegida, o sin elegir (el selector), no es Berry Med. */
    private fun berryMedIsHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    /** Junta lo que dice Android y pinta la lista; qué está hecho lo decide [KioskChecklist]. */
    private fun renderChecklist() {
        if (!::binding.isInitialized) return
        val types = runtime().foregroundTypes
        val typed = binding.kioskUrl.text?.toString()?.trim().orEmpty()
        val input = KioskChecklistInput(
            cameraGranted = granted(Manifest.permission.CAMERA),
            microphoneGranted = granted(Manifest.permission.RECORD_AUDIO),
            berryMedIsHome = berryMedIsHome(),
            sdkInt = Build.VERSION.SDK_INT,
            serviceCamera = ForegroundAccess.hasCamera(types),
            serviceMicrophone = ForegroundAccess.hasMicrophone(types),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            kioskUrlSaved = typed.isNotEmpty() && kioskUrlAccepted(typed) && typed == savedKioskUrl,
        )
        for (item in KioskChecklist.items(input)) {
            val done = item.done == true
            when (item.check) {
                KioskCheck.PERMISSIONS -> paintCheck(
                    binding.checkPermissions, item, R.string.check_permissions,
                    getString(if (done) R.string.check_permissions_done else R.string.check_permissions_missing),
                    R.string.check_permissions_action,
                )
                KioskCheck.HOME -> paintCheck(
                    binding.checkHome, item, R.string.check_home,
                    getString(if (done) R.string.check_home_done else R.string.check_home_missing),
                    R.string.check_home_action,
                )
                KioskCheck.SERVICE -> paintCheck(
                    binding.checkService, item, R.string.check_service,
                    getString(if (done) R.string.check_service_done else R.string.check_service_missing),
                    null,
                )
                KioskCheck.BATTERY -> paintCheck(
                    binding.checkBattery, item, R.string.check_battery,
                    getString(if (done) R.string.check_battery_done else R.string.check_battery_missing),
                    R.string.battery,
                )
                KioskCheck.TOTEM -> paintCheck(
                    binding.checkTotem, item, R.string.check_totem,
                    if (item.actionEnabled) {
                        getString(R.string.check_totem_detail)
                    } else {
                        getString(R.string.check_totem_detail) + "\n" + getString(R.string.check_totem_needs_url)
                    },
                    R.string.check_totem_action,
                )
            }
        }
    }

    /** Verde hecho, ámbar falta; gris cuando Berry Med no lo puede saber. */
    private fun paintCheck(row: ItemKioskCheckBinding, item: KioskCheckItem, title: Int, detail: String, action: Int?) {
        val color = when (item.done) {
            true -> R.color.ok
            false -> R.color.warn
            null -> R.color.ink_soft
        }
        row.dot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        row.title.setText(title)
        row.detail.text = detail
        row.action.visibility = if (item.action && action != null) View.VISIBLE else View.GONE
        if (action != null) row.action.setText(action)
        row.action.isEnabled = item.actionEnabled
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    private fun fill(config: BerryConfig) {
        filling = true
        select(binding.environment, ENVIRONMENT_VALUES, config.environment)
        binding.apiUrl.setText(config.apiBaseUrl)
        binding.totemId.setText(config.totemId)
        binding.pusherKey.setText(config.pusherKey)
        binding.pusherCluster.setText(config.pusherCluster, false)
        binding.channel.setText(config.publicChannel)
        binding.startEvent.setText(config.startEventName)
        binding.stopEvent.setText(config.stopEventName)
        binding.nibpTarget.setText(config.nibpTargetPressureMmhg)
        binding.ykushOtoscope.setText(config.ykushOtoscopePort.toString())
        binding.ykushStethoscope.setText(config.ykushStethoscopePort.toString())
        binding.ykushHeadset.setText(config.ykushHeadsetPort.toString())
        binding.localPin.setText(config.localPanelPin)
        binding.stethoInputName.setText(config.stethoInputName)
        binding.headsetName.setText(config.headsetName)
        binding.kioskUrl.setText(config.kioskUrl)
        select(binding.ecgGain, GAIN_VALUES, config.ecgGain)
        select(binding.ecgMode, MODE_VALUES, config.ecgMode)
        select(binding.respGain, GAIN_VALUES, config.respGain)
        select(binding.nibpMode, NIBP_VALUES, config.nibpMode)
        filling = false
        preview()
    }

    /** Al elegir un entorno se cargan sus valores; antes de guardar se pueden cambiar. */
    private fun applyEnvironment() {
        val environment = Environment.fromKey(value(binding.environment, ENVIRONMENT_VALUES))
        binding.apiUrl.setText(environment.apiBaseUrl)
        binding.pusherKey.setText(environment.pusherKey)
        binding.pusherCluster.setText(environment.pusherCluster, false)
    }

    /** El canal sigue al identificador del tótem mientras nadie lo haya cambiado a mano. */
    private inner class ChannelFollower : TextWatcher {
        private var previous = ""

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            previous = s?.toString().orEmpty()
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (filling) return
            val channel = binding.channel.text?.toString()?.trim().orEmpty()
            if (channel.isNotEmpty() && channel != defaultChannel(previous)) return
            binding.channel.setText(defaultChannel(s?.toString().orEmpty()))
        }
    }

    private fun advance() {
        val missing = missingField(step)
        if (missing != null) {
            binding.formMessage.setText(missing)
            binding.formMessage.setTextColor(getColor(R.color.form_error))
            return
        }
        clearMessage()
        showStep(step + 1)
    }

    /** Las etiquetas de arriba, en el orden de los hijos de `steps`. */
    private fun stepLabels() = listOf(binding.stepDestination, binding.stepOrders, binding.stepDevice, binding.stepKiosk)

    /** El último paso sale de los hijos del `ViewFlipper`: sumar un paso no obliga a tocar los topes. */
    private fun lastStep() = binding.steps.childCount - 1

    private fun showStep(index: Int) {
        val last = lastStep()
        step = index.coerceIn(0, last)
        binding.steps.displayedChild = step
        binding.backButton.visibility = if (step == 0) View.GONE else View.VISIBLE
        binding.nextButton.visibility = if (step == last) View.GONE else View.VISIBLE
        binding.saveButton.visibility = if (step == last) View.VISIBLE else View.GONE
        if (step == STEP_KIOSK) renderChecklist()
        stepLabels().forEachIndexed { i, label ->
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
            else -> null
        }
        1 -> when {
            binding.pusherKey.text.isNullOrBlank() -> R.string.missing_pusher
            binding.channel.text.isNullOrBlank() -> R.string.missing_channel
            else -> null
        }
        2 -> if (readForm().ykushPortsShared()) R.string.ykush_shared else null
        STEP_KIOSK -> if (kioskUrlAccepted(binding.kioskUrl.text?.toString().orEmpty())) null else R.string.missing_kiosk_url
        else -> null
    }

    private fun clearMessage() {
        binding.formMessage.text = ""
    }

    private fun save() {
        val blocked = (0..lastStep()).firstNotNullOfOrNull { index ->
            missingField(index)?.let { index to it }
        }
        if (blocked != null) {
            showStep(blocked.first)
            binding.formMessage.setText(blocked.second)
            binding.formMessage.setTextColor(getColor(R.color.form_error))
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
                if (saved) {
                    savedKioskUrl = config.kioskUrl
                    renderChecklist()
                }
                binding.formMessage.setTextColor(getColor(if (saved) R.color.signal else R.color.form_error))
                binding.formMessage.setText(if (saved) R.string.saved else R.string.save_failed)
            }
        }
    }

    private fun readForm(): BerryConfig {
        val totemId = binding.totemId.text?.toString()?.trim().orEmpty()
        val environment = Environment.fromKey(value(binding.environment, ENVIRONMENT_VALUES))
        return BerryConfig(
            environment = environment.key,
            apiBaseUrl = binding.apiUrl.text?.toString()?.trim().orEmpty(),
            totemId = totemId,
            pusherKey = binding.pusherKey.text?.toString()?.trim().orEmpty(),
            pusherCluster = binding.pusherCluster.text?.toString()?.trim().orEmpty().ifBlank { environment.pusherCluster },
            publicChannel = binding.channel.text?.toString()?.trim().orEmpty().ifBlank { defaultChannel(totemId) },
            startEventName = binding.startEvent.text?.toString()?.trim().orEmpty().ifBlank { "start-monitoring" },
            stopEventName = binding.stopEvent.text?.toString()?.trim().orEmpty().ifBlank { "stop-monitoring" },
            ecgGain = value(binding.ecgGain, GAIN_VALUES),
            ecgMode = value(binding.ecgMode, MODE_VALUES),
            respGain = value(binding.respGain, GAIN_VALUES),
            nibpMode = value(binding.nibpMode, NIBP_VALUES),
            nibpTargetPressureMmhg = binding.nibpTarget.text?.toString()?.trim().orEmpty(),
            ykushOtoscopePort = ykushPortOrDefault(binding.ykushOtoscope.text?.toString(), 1),
            ykushStethoscopePort = ykushPortOrDefault(binding.ykushStethoscope.text?.toString(), 2),
            ykushHeadsetPort = ykushPortOrDefault(binding.ykushHeadset.text?.toString(), 3),
            localPanelPin = binding.localPin.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_LOCAL_PIN },
            stethoInputName = binding.stethoInputName.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_STETHO_INPUT },
            headsetName = binding.headsetName.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_HEADSET },
            kioskUrl = binding.kioskUrl.text?.toString()?.trim().orEmpty(),
        )
    }

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
        binding.formMessage.setTextColor(getColor(R.color.form_error))
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
        /** El paso Kiosco, el cuarto hijo de `steps`. */
        private const val STEP_KIOSK = 3
        private const val REQUEST_STARTUP = 1
        private const val REQUEST_MEDIA = 2
        private val MEDIA_PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        /** En el orden de los chips: Producción, Dev. */
        private val ENVIRONMENT_VALUES = arrayOf(Environment.Prod.key, Environment.Dev.key)
        private val GAIN_VALUES = arrayOf("x0.25", "x0.5", "x1", "x2")
        private val MODE_VALUES = arrayOf("operacion", "monitor", "diagnostico")
        private val NIBP_VALUES = arrayOf("adulto", "nino", "neonato")
    }
}
