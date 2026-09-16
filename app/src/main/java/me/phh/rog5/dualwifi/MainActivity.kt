package me.phh.rog5.dualwifi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.phh.rog5.dualwifi.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DualWifi_UI"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: DualWifiManager
    private var pollJob: Job? = null

    private val prefs by lazy { getSharedPreferences("dual_wifi_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DualWifiLogger.i(TAG, "MainActivity onCreate: Initializing Dual Wi-Fi UI")
        wifiManager = DualWifiManager(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        observeLogs()
        checkRootAccess()
    }

    override fun onResume() {
        super.onResume()
        DualWifiLogger.d(TAG, "MainActivity onResume: Resuming telemetry polling")
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        DualWifiLogger.d(TAG, "MainActivity onPause: Pausing telemetry polling")
        pollJob?.cancel()
    }

    private fun setupUI() {
        val savedSsid = prefs.getString("saved_ssid", null)
        if (savedSsid != null) {
            binding.tvSecondarySsid.text = savedSsid
            binding.btnSelectNetwork.text = "Change Network ($savedSsid)"
            DualWifiLogger.d(TAG, "Restored saved secondary network: $savedSsid")
        }
    }

    private fun checkRootAccess() {
        lifecycleScope.launch {
            val rootOk = RootShell.isRootAvailable()
            if (!rootOk) {
                DualWifiLogger.e(TAG, "Superuser access not available! Commands may fail.")
                Toast.makeText(
                    this@MainActivity,
                    "Warning: Superuser permission not detected. Please verify KernelSU / Magisk grant.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                DualWifiLogger.i(TAG, "Superuser access verified and active.")
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            DualWifiLogger.logsFlow.collect { entries ->
                if (entries.isNotEmpty()) {
                    val logText = entries.takeLast(40).joinToString("\n") { it.formatted() }
                    binding.tvLog.text = logText
                    binding.scrollLogs.post {
                        binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // Master Switch Toggle
        binding.switchDualWifi.setOnCheckedChangeListener { _, isChecked ->
            DualWifiLogger.i(TAG, "Master toggle switched: isChecked=$isChecked")
            if (isChecked) {
                val savedSsid = prefs.getString("saved_ssid", null)
                val savedPass = if (savedSsid != null) wifiManager.getSavedPassword(savedSsid) ?: prefs.getString("saved_pass", "") ?: "" else ""
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
            DualWifiLogger.i(TAG, "User clicked Scan & Select Secondary Network")
            showNetworkPicker()
        }

        binding.btnSelectNetwork.setOnLongClickListener {
            showForgetNetworkDialog()
            true
        }

        // Mode Toggles
        binding.rbModeGaming.setOnClickListener {
            binding.rbModeGaming.isChecked = true
            binding.rbModeSpeed.isChecked = false
            DualWifiLogger.i(TAG, "Acceleration mode set to Gaming Low-Latency (SLS)")
        }
        binding.layoutModeGaming.setOnClickListener { binding.rbModeGaming.performClick() }

        binding.rbModeSpeed.setOnClickListener {
            binding.rbModeSpeed.isChecked = true
            binding.rbModeGaming.isChecked = false
            DualWifiLogger.i(TAG, "Acceleration mode set to Download Booster (SLA)")
        }
        binding.layoutModeSpeed.setOnClickListener { binding.rbModeSpeed.performClick() }

        // Advanced Diagnostics Drawer Toggle
        binding.layoutToggleDebug.setOnClickListener {
            val isVisible = binding.layoutDebugContent.visibility == View.VISIBLE
            binding.layoutDebugContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvToggleArrow.text = if (isVisible) "Show" else "Hide"
            DualWifiLogger.d(TAG, "Diagnostics drawer toggled: visible=${!isVisible}")
        }

        // Run Self-Test Button
        binding.btnRunSelfTest.setOnClickListener {
            runSelfTestDiagnostics()
        }

        // Copy Logs Button
        binding.btnCopyLogs.setOnClickListener {
            val logs = DualWifiLogger.getAllLogs()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DualWifi Logs", logs))
            Toast.makeText(this, "Diagnostics logs copied to clipboard (${logs.lines().size} lines)", Toast.LENGTH_SHORT).show()
            DualWifiLogger.d(TAG, "Logs copied to clipboard by user")
        }

        // Clear Logs Button
        binding.btnClearLogs.setOnClickListener {
            DualWifiLogger.clear()
            binding.tvLog.text = "Logs cleared.\n"
        }
    }

    private fun runSelfTestDiagnostics() {
        lifecycleScope.launch {
            binding.btnRunSelfTest.isEnabled = false
            binding.btnRunSelfTest.text = "Testing..."
            DualWifiLogger.i(TAG, "User triggered system self-test...")

            val report = wifiManager.runSelfTest()
            binding.btnRunSelfTest.isEnabled = true
            binding.btnRunSelfTest.text = "Run Self-Test"

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Dual Wi-Fi Self-Test")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy Report") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DualWifi Report", report))
                    Toast.makeText(this@MainActivity, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun showNetworkPicker() {
        lifecycleScope.launch {
            binding.btnSelectNetwork.isEnabled = false
            binding.btnSelectNetwork.text = "Scanning nearby networks..."
            DualWifiLogger.i(TAG, "Scanning for 5 GHz and 2.4 GHz access points...")

            val networks = wifiManager.scanNetworks()
            binding.btnSelectNetwork.isEnabled = true
            val currentSsid = prefs.getString("saved_ssid", null)
            binding.btnSelectNetwork.text = if (currentSsid != null) "Change Network ($currentSsid)" else "Scan & Select Secondary Network"

            if (networks.isEmpty()) {
                DualWifiLogger.w(TAG, "Scan returned 0 networks")
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("No Networks Found")
                    .setMessage("No Wi-Fi networks were discovered.\n\nPlease ensure Wi-Fi is enabled in Android Settings and check the Diagnostics log drawer for details.")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("View Logs") { _, _ ->
                        binding.layoutDebugContent.visibility = View.VISIBLE
                        binding.tvToggleArrow.text = "Hide"
                    }
                    .show()
                return@launch
            }

            DualWifiLogger.i(TAG, "Presenting ${networks.size} networks to user")
            val items = networks.map { net ->
                val badge = if (net.is5GHz) "5 GHz DBS" else "2.4 GHz"
                val savedBadge = if (wifiManager.isNetworkSaved(net.ssid)) " • Saved" else if (net.isOpen) " • Open" else ""
                "${net.ssid}\n[$badge]$savedBadge • Signal: ${net.level} dBm"
            }.toTypedArray()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Select Secondary Wi-Fi (${networks.size} found)")
                .setItems(items) { _, which ->
                    val selected = networks[which]
                    DualWifiLogger.i(TAG, "User selected network: '${selected.ssid}' (${selected.bandLabel}, ${selected.freq} MHz, saved=${wifiManager.isNetworkSaved(selected.ssid)})")

                    if (wifiManager.isNetworkSaved(selected.ssid)) {
                        val savedPass = wifiManager.getSavedPassword(selected.ssid) ?: ""
                        Toast.makeText(this@MainActivity, "Connecting to ${selected.ssid} (Saved network)...", Toast.LENGTH_SHORT).show()
                        prefs.edit().putString("saved_ssid", selected.ssid).putString("saved_pass", savedPass).apply()
                        binding.tvSecondarySsid.text = selected.ssid
                        binding.btnSelectNetwork.text = "Change Network (${selected.ssid})"
                        binding.switchDualWifi.isChecked = true
                        connectToSecondary(selected.ssid, savedPass)
                    } else if (selected.isOpen) {
                        Toast.makeText(this@MainActivity, "Connecting to open network ${selected.ssid}...", Toast.LENGTH_SHORT).show()
                        wifiManager.saveNetworkCredentials(selected.ssid, "")
                        binding.tvSecondarySsid.text = selected.ssid
                        binding.btnSelectNetwork.text = "Change Network (${selected.ssid})"
                        binding.switchDualWifi.isChecked = true
                        connectToSecondary(selected.ssid, "")
                    } else {
                        promptPasswordAndConnect(selected.ssid)
                    }
                }
                .setNeutralButton("Forget Saved...") { _, _ ->
                    showForgetNetworkDialog()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    DualWifiLogger.d(TAG, "Network selection canceled by user")
                }
                .show()
        }
    }

    private fun showForgetNetworkDialog() {
        val savedSsid = prefs.getString("saved_ssid", null)
        if (savedSsid == null && !wifiManager.isNetworkSaved("")) {
            Toast.makeText(this, "No saved networks to remove", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Forget Saved Network")
            .setMessage("Do you want to forget saved credentials for '$savedSsid'?")
            .setPositiveButton("Forget") { _, _ ->
                savedSsid?.let { wifiManager.forgetNetworkCredentials(it) }
                binding.tvSecondarySsid.text = "Secondary Wi-Fi"
                binding.btnSelectNetwork.text = "Scan & Select Secondary Network"
                Toast.makeText(this, "Network forgotten", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPasswordAndConnect(ssid: String) {
        val input = EditText(this).apply {
            hint = "Wi-Fi Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Connect to $ssid")
            .setMessage("Enter password (network will be saved for automatic reuse):")
            .setView(input)
            .setPositiveButton("Connect & Save") { _, _ ->
                val pass = input.text.toString()
                wifiManager.saveNetworkCredentials(ssid, pass)
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
            DualWifiLogger.i(TAG, "Activating dual Wi-Fi with $ssid...")

            // Auto spawn if needed
            wifiManager.spawnWlan1 { DualWifiLogger.i(TAG, it) }

            val success = wifiManager.connectSecondary(ssid, pass) { DualWifiLogger.i(TAG, it) }
            if (success) {
                binding.tvSecondarySub.text = "Connected & Accelerated"
                binding.tvSecondaryBadge.text = "Accelerated"
                binding.switchDualWifi.isChecked = true
                DualWifiLogger.i(TAG, "Dual Wi-Fi successfully engaged with $ssid")
            } else {
                binding.tvSecondarySub.text = "Connection failed - tap to retry"
                binding.tvSecondaryBadge.text = "Failed"
                binding.switchDualWifi.isChecked = false
                DualWifiLogger.e(TAG, "Dual Wi-Fi connection failed to $ssid")
            }
            refreshStatus()
        }
    }

    private fun disconnectSecondary() {
        lifecycleScope.launch {
            binding.tvSecondarySub.text = "Disconnected"
            binding.tvSecondaryBadge.text = "Offline"
            wifiManager.disconnectSecondary { DualWifiLogger.i(TAG, it) }
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
            binding.tvSecondaryBadge.text = "5 GHz DBS"
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
}
