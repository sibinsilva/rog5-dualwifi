package me.phh.rog5.dualwifi

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.phh.rog5.dualwifi.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val wifiManager = DualWifiManager()
    private var pollJob: Job? = null

    private val prefs by lazy { getSharedPreferences("dual_wifi_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    private fun setupUI() {
        val savedSsid = prefs.getString("saved_ssid", null)
        if (savedSsid != null) {
            binding.tvSecondarySsid.text = savedSsid
            binding.btnSelectNetwork.text = "Change Network ($savedSsid)"
        }
    }

    private fun setupListeners() {
        // Master Switch Toggle
        binding.switchDualWifi.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val savedSsid = prefs.getString("saved_ssid", null)
                val savedPass = prefs.getString("saved_pass", "") ?: ""
                if (savedSsid.isNullOrEmpty()) {
                    showNetworkPicker()
                } else {
                    connectToSecondary(savedSsid, savedPass)
                }
            } else {
                disconnectSecondary()
            }
        }

        // Scan & Select Secondary Network
        binding.btnSelectNetwork.setOnClickListener {
            showNetworkPicker()
        }

        // Mode Toggles
        binding.rbModeGaming.setOnClickListener {
            binding.rbModeGaming.isChecked = true
            binding.rbModeSpeed.isChecked = false
            log("Acceleration mode set to Gaming Low-Latency (SLS)")
        }
        binding.layoutModeGaming.setOnClickListener { binding.rbModeGaming.performClick() }

        binding.rbModeSpeed.setOnClickListener {
            binding.rbModeSpeed.isChecked = true
            binding.rbModeGaming.isChecked = false
            log("Acceleration mode set to Download Booster (SLA)")
        }
        binding.layoutModeSpeed.setOnClickListener { binding.rbModeSpeed.performClick() }

        // Advanced Diagnostics Drawer Toggle
        binding.layoutToggleDebug.setOnClickListener {
            val isVisible = binding.layoutDebugContent.visibility == View.VISIBLE
            binding.layoutDebugContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvToggleArrow.text = if (isVisible) "▼ Show" else "▲ Hide"
        }
    }

    private fun showNetworkPicker() {
        lifecycleScope.launch {
            binding.btnSelectNetwork.isEnabled = false
            binding.btnSelectNetwork.text = "Scanning nearby networks..."
            log("Scanning for nearby 5 GHz and 2.4 GHz access points...")

            val networks = wifiManager.scanNetworks()
            binding.btnSelectNetwork.isEnabled = true
            val savedSsid = prefs.getString("saved_ssid", null)
            binding.btnSelectNetwork.text = if (savedSsid != null) "Change Network ($savedSsid)" else "Scan & Select Secondary Network"

            if (networks.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("No Networks Found")
                    .setMessage("Make sure Wi-Fi is enabled and location permission is granted.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val items = networks.map { net ->
                val badge = if (net.is5GHz) "⚡ 5 GHz DBS" else "2.4 GHz"
                "${net.ssid}\n[$badge] • Signal: ${net.level} dBm"
            }.toTypedArray()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Select Secondary Wi-Fi")
                .setItems(items) { _, which ->
                    val selected = networks[which]
                    promptPasswordAndConnect(selected.ssid)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun promptPasswordAndConnect(ssid: String) {
        val input = EditText(this).apply {
            hint = "Wi-Fi Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Connect to $ssid")
            .setMessage("Enter the password for your secondary network:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val pass = input.text.toString()
                prefs.edit().putString("saved_ssid", ssid).putString("saved_pass", pass).apply()
                binding.tvSecondarySsid.text = ssid
                binding.btnSelectNetwork.text = "Change Network ($ssid)"
                binding.switchDualWifi.isChecked = true
                connectToSecondary(ssid, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToSecondary(ssid: String, pass: String) {
        lifecycleScope.launch {
            binding.tvSecondarySub.text = "Connecting to $ssid..."
            binding.tvSecondaryBadge.text = "Connecting..."
            log("Activating dual Wi-Fi with $ssid...")

            // Auto spawn if needed
            wifiManager.spawnWlan1 { log(it) }

            val success = wifiManager.connectSecondary(ssid, pass) { log(it) }
            if (success) {
                binding.tvSecondarySub.text = "Connected & Accelerated"
                binding.tvSecondaryBadge.text = "⚡ Accelerated"
                binding.switchDualWifi.isChecked = true
            } else {
                binding.tvSecondarySub.text = "Connection failed - tap to retry"
                binding.tvSecondaryBadge.text = "Failed"
                binding.switchDualWifi.isChecked = false
            }
            refreshStatus()
        }
    }

    private fun disconnectSecondary() {
        lifecycleScope.launch {
            binding.tvSecondarySub.text = "Disconnected"
            binding.tvSecondaryBadge.text = "Offline"
            wifiManager.disconnectSecondary { log(it) }
            refreshStatus()
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refreshStatus()
                delay(3000)
            }
        }
    }

    private suspend fun refreshStatus() {
        val w0 = wifiManager.getInterfaceStatus("wlan0")
        val w1 = wifiManager.getInterfaceStatus("wlan1")
        val sla = wifiManager.getSlaStatus()

        // Update Primary (wlan0)
        binding.tvPrimarySsid.text = w0.ssid ?: "Not Connected"
        binding.tvPrimarySub.text = if (w0.isUp && w0.ip != null) "IP: ${w0.ip}" else "Offline"
        binding.tvPrimaryBadge.text = if (w0.freq > 4000) "5 GHz" else "2.4 GHz"

        // Update Secondary (wlan1)
        if (w1.isUp && w1.ip != null) {
            binding.tvSecondarySsid.text = w1.ssid ?: "Secondary Wi-Fi"
            binding.tvSecondarySub.text = "IP: ${w1.ip} • Accelerated"
            binding.tvSecondaryBadge.text = "⚡ 5 GHz DBS"
            binding.ivSecondaryIcon.setColorFilter(getColor(com.google.android.material.R.color.material_dynamic_primary40))
        } else if (w1.isUp) {
            binding.tvSecondarySub.text = "Antenna ready, connecting..."
            binding.tvSecondaryBadge.text = "Ready"
        }

        // Update Advanced Diagnostics text
        binding.tvAdvancedStats.text = "Kernel Node: /proc/sla/config (${if (sla.isEnabled) "enable=1" else "idle"})\n" +
                "Daemon: ${if (sla.daemonRunning) "slad-v2 (Active)" else "Stopped"}\n" +
                "wlan0 Traffic: ${formatBytes(sla.bytesWlan0)} | wlan1 Traffic: ${formatBytes(sla.bytesWlan1)}"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private fun log(msg: String) {
        val current = binding.tvLog.text.toString()
        val lines = current.lines().takeLast(6).joinToString("\n")
        binding.tvLog.text = "$lines\n> $msg"
    }
}
