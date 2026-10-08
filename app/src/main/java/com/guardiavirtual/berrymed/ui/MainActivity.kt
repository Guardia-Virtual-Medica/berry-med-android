package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.guardiavirtual.berrymed.config.ConfigStore
import com.guardiavirtual.berrymed.config.TotemConfig
import com.guardiavirtual.berrymed.databinding.ActivityMainBinding
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.MonitorState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private lateinit var usbManager: UsbManager

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                startService(
                    Intent(this@MainActivity, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_USB_CHANGED),
                )
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        requestUsbPermissionsForAll()
        startMonitor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ConfigStore(this)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        lifecycleScope.launch {
            configStore.configFlow.collectLatest { cfg -> bindConfigToForm(cfg) }
        }
        lifecycleScope.launch {
            MonitorState.state.collectLatest { state -> bindState(state) }
        }

        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                val cfg = readConfigFromForm()
                configStore.save(cfg)
                Toast.makeText(this@MainActivity, "Configuración guardada", Toast.LENGTH_SHORT).show()
                startService(
                    Intent(this@MainActivity, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_RELOAD_CONFIG),
                )
            }
        }
        binding.btnStartService.setOnClickListener { ensurePermissionsAndStart() }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }

        ensurePermissionsAndStart()
        handleUsbIntent(intent)
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        requestUsbPermission(device)
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            requestUsbPermissionsForAll()
            startMonitor()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestUsbPermissionsForAll() {
        usbManager.deviceList.values.forEach { requestUsbPermission(it) }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) return
        val pi = android.app.PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    private fun startMonitor() {
        MonitorService.start(this)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }

    private fun bindConfigToForm(cfg: TotemConfig) {
        binding.inputApiBase.setText(cfg.apiBaseUrl)
        binding.inputTotemId.setText(cfg.totemId)
        binding.inputApiEndpoint.setText(cfg.apiEndpoint)
        binding.inputApiUser.setText(cfg.apiUsername)
        binding.inputApiPass.setText(cfg.apiPassword)
        binding.inputPusherKey.setText(cfg.pusherKey)
        binding.inputPusherCluster.setText(cfg.pusherCluster)
        binding.inputChannel.setText(cfg.publicChannel)
        binding.inputStartEvent.setText(cfg.startEventName)
        binding.inputStopEvent.setText(cfg.stopEventName)
        binding.inputMaxSession.setText(cfg.maxSessionMinutes.toString())
        binding.inputEcgGain.setText(cfg.ecgGain)
        binding.inputEcgMode.setText(cfg.ecgMode)
        binding.checkboxTherm.isChecked = cfg.thermometerEnabled
        binding.textMetricsPreview.text = cfg.metricsUrl().ifBlank { "—" }
    }

    private fun readConfigFromForm(): TotemConfig = TotemConfig(
        apiBaseUrl = binding.inputApiBase.text?.toString().orEmpty(),
        totemId = binding.inputTotemId.text?.toString().orEmpty(),
        apiEndpoint = binding.inputApiEndpoint.text?.toString().orEmpty().ifBlank { "metrics" },
        apiUsername = binding.inputApiUser.text?.toString().orEmpty(),
        apiPassword = binding.inputApiPass.text?.toString().orEmpty(),
        pusherKey = binding.inputPusherKey.text?.toString().orEmpty(),
        pusherCluster = binding.inputPusherCluster.text?.toString().orEmpty().ifBlank { "us2" },
        publicChannel = binding.inputChannel.text?.toString().orEmpty(),
        startEventName = binding.inputStartEvent.text?.toString().orEmpty().ifBlank { "start-monitoring" },
        stopEventName = binding.inputStopEvent.text?.toString().orEmpty().ifBlank { "stop-monitoring" },
        maxSessionMinutes = binding.inputMaxSession.text?.toString()?.toIntOrNull() ?: 30,
        ecgGain = binding.inputEcgGain.text?.toString().orEmpty().ifBlank { "x1" },
        ecgMode = binding.inputEcgMode.text?.toString().orEmpty().ifBlank { "monitor" },
        thermometerEnabled = binding.checkboxTherm.isChecked,
    )

    private fun bindState(state: com.guardiavirtual.berrymed.service.MonitorUiState) {
        binding.textStatus.text = state.statusLine
        binding.textBerry.text = if (state.berryConnected) "Conectada" else "Desconectada"
        binding.textTherm.text = if (state.thermConnected) "Conectado" else "Desconectado"
        binding.textRelay.text = if (state.relayPresent) "Presente" else "No detectado"
        binding.textPusher.text = if (state.pusherConnected) "Conectado" else "Desconectado"
        binding.textSession.text = if (state.sessionActive) "Activa" else "Inactiva"
        binding.textVitals.text = buildString {
            append("FC ${state.vitals.heartRate}  FR ${state.vitals.respRate}\n")
            append("SpO₂/pulso ${state.vitals.spo2Pulse}\n")
            append("Temp ${state.vitals.temperature}  PA ${state.vitals.nibp}")
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.guardiavirtual.berrymed.USB_PERMISSION"
    }
}
