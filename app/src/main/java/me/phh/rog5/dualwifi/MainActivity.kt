package me.phh.rog5.dualwifi

import android.graphics.Color
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            lifecycleScope.launch {
                binding.btnScan.isEnabled = false
                binding.btnScan.text = "Scanning..."
                log("Scanning nearby Wi-Fi networks...")

                val networks = wifiManager.scanNetworks()
                binding.btnScan.isEnabled = true
                binding.btnScan.text = "Scan Networks"

                if (networks.isEmpty()) {
                    log("No networks found or scan permission missing.")
                    return@launch
                }

                log("Found ${networks.size} networks. Showing list...")
                val items = networks.map { net ->
                    val bandBadge = if (net.is5GHz) "⚡ 5 GHz" else "2.4 GHz"
                    "${net.ssid}\n  [$bandBadge]  Signal: ${net.level} dBm"
                }.toTypedArray()

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Select Secondary Network")
                    .setItems(items) { _, which ->
                        val selected = networks[which]
                        binding.etSsid.setText(selected.ssid)
                        log("Selected: ${selected.ssid} (${selected.bandLabel})")
                        binding.etPassword.requestFocus()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.btnSpawnWlan1.setOnClickListener {
            lifecycleScope.launch {
                binding.btnSpawnWlan1.isEnabled = false
                wifiManager.spawnWlan1 { log(it) }
                binding.btnSpawnWlan1.isEnabled = true
                refreshStatus()
            }
        }

        binding.btnConnectDual.setOnClickListener {
            val ssid = binding.etSsid.text.toString().trim()
            val pass = binding.etPassword.text.toString()

            if (ssid.isEmpty()) {
                log("Please enter a secondary Wi-Fi SSID")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                binding.btnConnectDual.isEnabled = false
                wifiManager.connectSecondary(ssid, pass) { log(it) }
                binding.btnConnectDual.isEnabled = true
                refreshStatus()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            lifecycleScope.launch {
                binding.btnDisconnect.isEnabled = false
                wifiManager.disconnectSecondary { log(it) }
                binding.btnDisconnect.isEnabled = true
                refreshStatus()
            }
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

        // Update wlan0 Card
        binding.tvWlan0Status.text = if (w0.isUp) "ACTIVE" else "OFFLINE"
        binding.tvWlan0Status.setTextColor(if (w0.isUp) Color.parseColor("#00E5FF") else Color.GRAY)
        binding.tvWlan0Details.text = "SSID: ${w0.ssid ?: "Unknown"}\nIP: ${w0.ip ?: "-"}\nBand: ${if (w0.freq > 4000) "5 GHz" else "2.4 GHz"} (${w0.freq} MHz)"

        // Update wlan1 Card
        binding.tvWlan1Status.text = if (w1.isUp && w1.ip != null) "CONNECTED" else if (w1.isUp) "SPAWNED" else "OFFLINE"
        binding.tvWlan1Status.setTextColor(if (w1.ip != null) Color.parseColor("#00E676") else if (w1.isUp) Color.YELLOW else Color.GRAY)
        binding.tvWlan1Details.text = "SSID: ${w1.ssid ?: "-"}\nIP: ${w1.ip ?: "-"}\nBand: ${if (w1.freq > 4000) "5 GHz" else if (w1.freq > 0) "2.4 GHz" else "-"} (${w1.freq} MHz)"

        // Update SLA Card
        binding.tvSlaStatus.text = if (sla.isEnabled) "ACTIVE (BONDING)" else "STANDBY"
        binding.tvSlaStatus.setTextColor(if (sla.isEnabled) Color.parseColor("#00E676") else Color.GRAY)
        binding.tvSlaDetails.text = "Kernel Node: /proc/sla/config (${if (sla.isEnabled) "enable=1" else "idle"})\nDaemon: ${if (sla.daemonRunning) "slad-v2 (Running)" else "Stopped"}\nwlan0 Routed: ${formatBytes(sla.bytesWlan0)} | wlan1 Routed: ${formatBytes(sla.bytesWlan1)}"
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
        val lines = current.lines().takeLast(8).joinToString("\n")
        binding.tvLog.text = "$lines\n> $msg"
    }
}
