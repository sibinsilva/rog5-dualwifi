package me.phh.rog5.dualwifi

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DualWifiManager(private val context: Context) {

    companion object {
        private const val TAG = "DualWifi_Manager"
        private const val SOCKET_PATH = "/data/vendor/wifi/wpa/sockets"

        fun getSystemProperty(key: String, default: String = ""): String {
            return try {
                val spClass = Class.forName("android.os.SystemProperties")
                val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
                getMethod.invoke(null, key, default) as String
            } catch (_: Exception) {
                default
            }
        }
    }

    data class InterfaceInfo(
        val name: String,
        val isUp: Boolean,
        val ip: String?,
        val ssid: String? = null,
        val freq: Int = 0,
        val linkSpeed: Int = 0
    )

    data class SlaInfo(
        val isEnabled: Boolean,
        val daemonRunning: Boolean,
        val bytesWlan0: Long = 0,
        val bytesWlan1: Long = 0
    )

    data class ScannedNetwork(
        val ssid: String,
        val bssid: String,
        val freq: Int,
        val level: Int,
        val flags: String
    ) {
        val is5GHz: Boolean get() = freq > 4000
        val bandLabel: String get() = if (is5GHz) "5 GHz" else "2.4 GHz"
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    // ---------------------------------------------------------------------------
    // Scanning — uses Android WifiManager directly, no root required
    // ---------------------------------------------------------------------------
    suspend fun scanNetworks(): List<ScannedNetwork> = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Starting network scan via WifiManager API...")
        val networks = mutableListOf<ScannedNetwork>()

        // Trigger a fresh scan (may be throttled on Android 10+, but cached
        // results are always available immediately via getScanResults())
        @Suppress("DEPRECATION")
        val triggered = wifiManager.startScan()
        DualWifiLogger.d(TAG, "WifiManager.startScan() returned: $triggered")

        // Small delay so the scan result broadcast can arrive
        kotlinx.coroutines.delay(800)

        // Read the framework scan cache — always populated regardless of throttle
        val results: List<ScanResult> = wifiManager.scanResults ?: emptyList()
        DualWifiLogger.i(TAG, "WifiManager.getScanResults() returned ${results.size} raw entries")

        for (sr in results) {
            val ssid = sr.SSID?.trim('"') ?: continue
            if (ssid.isEmpty()) continue
            val flags = sr.capabilities ?: ""
            networks.add(ScannedNetwork(ssid, sr.BSSID ?: "", sr.frequency, sr.level, flags))
            DualWifiLogger.v(TAG, "  Found: '$ssid' ${sr.frequency}MHz ${sr.level}dBm")
        }

        // Also try wpa_cli as supplementary source (may work if root succeeds)
        val wpaRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan0 scan_results")
        if (wpaRes.isSuccess && wpaRes.output.contains("\t")) {
            var wpaExtra = 0
            for (line in wpaRes.output.lines()) {
                val parts = line.split("\t")
                if (parts.size >= 5) {
                    val bssid = parts[0].trim()
                    val freq = parts[1].trim().toIntOrNull() ?: 0
                    val level = parts[2].trim().toIntOrNull() ?: 0
                    val flags = parts[3].trim()
                    val ssid = parts[4].trim()
                    // Only add if not already in the list from WifiManager
                    if (ssid.isNotEmpty() && networks.none { it.bssid == bssid }) {
                        networks.add(ScannedNetwork(ssid, bssid, freq, level, flags))
                        wpaExtra++
                    }
                }
            }
            if (wpaExtra > 0) DualWifiLogger.d(TAG, "wpa_cli added $wpaExtra extra networks not in framework cache")
        }

        // Deduplicate by SSID + band, prioritising 5 GHz and strongest signal
        val deduped = networks
            .distinctBy { "${it.ssid}_${it.is5GHz}" }
            .sortedWith(compareByDescending<ScannedNetwork> { it.is5GHz }.thenByDescending { it.level })

        val n5 = deduped.count { it.is5GHz }
        val n24 = deduped.count { !it.is5GHz }
        DualWifiLogger.i(TAG, "Scan done: ${deduped.size} unique networks ($n5 on 5 GHz, $n24 on 2.4 GHz)")
        deduped
    }

    // ---------------------------------------------------------------------------
    // Enable Multi-STA / Dual Wi-Fi mode — mirrors Rog.kt applyDualWifi()
    //   mode 0 = disabled, 1 = DBS multi-AP, 2 = MCC time-sharing
    // ---------------------------------------------------------------------------
    suspend fun enableMultiInternetMode(mode: Int = 2): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Enabling Multi-Internet mode=$mode (mirrors treble Rog.applyDualWifi)")

        var ok = false

        // 1. WifiManager reflection (same as treble app, works without explicit root)
        try {
            val method = wifiManager.javaClass.getMethod(
                "setStaConcurrencyForMultiInternetMode", Int::class.javaPrimitiveType
            )
            val res = method.invoke(wifiManager, mode)
            DualWifiLogger.i(TAG, "setStaConcurrencyForMultiInternetMode($mode) = $res")
            ok = true
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "Reflection setStaConcurrencyForMultiInternetMode failed: ${t.message}")
        }

        // 2. Settings.Global wifi_multi_internet_mode
        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver, "wifi_multi_internet_mode", mode
            )
            DualWifiLogger.d(TAG, "Settings.Global wifi_multi_internet_mode=$mode written")
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "Settings.Global write failed: ${t.message}")
        }

        // 3. cmd wifi shell commands (same as treble app, via root)
        val cmdStr = if (mode > 0) {
            "cmd wifi force-overlay-config-value bool config_wifiMultiStaMultiInternetConcurrencyEnabled enabled true && cmd wifi set-multi-internet-mode $mode"
        } else {
            "cmd wifi set-multi-internet-mode 0"
        }
        val cmdRes = RootShell.run(cmdStr)
        DualWifiLogger.d(TAG, "cmd wifi set-multi-internet-mode: exit=${cmdRes.exitCode} out=${cmdRes.output.take(120)}")
        if (cmdRes.isSuccess) ok = true

        ok
    }

    // ---------------------------------------------------------------------------
    // Enable HyperFusion SLA — mirrors Rog.kt applyHyperFusion()
    // ---------------------------------------------------------------------------
    suspend fun enableHyperFusion(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enable) "1" else "0"
        DualWifiLogger.i(TAG, "Applying HyperFusion SLA enabled=$value")

        var ok = false
        val cmdStr = "setprop vendor.sla.enabled $value && setprop persist.vendor.sla.enabled $value"
        val res = RootShell.run(cmdStr)
        DualWifiLogger.d(TAG, "setprop SLA: exit=${res.exitCode} out=${res.output.take(80)}")
        if (res.isSuccess) ok = true

        // Also write /proc/sla/config directly
        if (enable) {
            val slaRes = RootShell.run("echo 'enable=1' > /proc/sla/config 2>/dev/null")
            DualWifiLogger.d(TAG, "/proc/sla/config write: exit=${slaRes.exitCode}")
        }

        ok
    }

    // ---------------------------------------------------------------------------
    // Spawn secondary wlan1 interface (optional helper)
    // ---------------------------------------------------------------------------
    suspend fun spawnWlan1(logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Spawning secondary wlan1 interface...")
        logger("Requesting wlan1 interface spawn via wificond...")

        val res = RootShell.run("service call wifinl80211 1 s16 'wlan1'")
        logger("wificond response: ${res.output.lines().firstOrNull() ?: "OK"}")

        val check = RootShell.run("ip link show wlan1")
        val success = check.isSuccess && !check.output.contains("does not exist")
        if (success) {
            DualWifiLogger.i(TAG, "wlan1 interface is present, bringing UP")
            logger("wlan1 created successfully!")
            RootShell.run("ip link set dev wlan1 up")
        } else {
            DualWifiLogger.w(TAG, "wlan1 not created directly by wificond (${check.output.trim()})")
            logger("Notice: wlan1 was not spawned by wificond directly (${check.output.trim()}).")
        }
        success
    }

    // ---------------------------------------------------------------------------
    // Interface status — uses WifiManager for wlan0, root for wlan1
    // ---------------------------------------------------------------------------
    suspend fun getInterfaceStatus(iface: String): InterfaceInfo = withContext(Dispatchers.IO) {
        if (iface == "wlan0") {
            // Primary: read directly from WifiManager — no root needed
            val connInfo = @Suppress("DEPRECATION") wifiManager.connectionInfo
            val ssid = connInfo?.ssid?.trim('"').takeIf { !it.isNullOrEmpty() && it != "<unknown ssid>" }
            val freq = connInfo?.frequency ?: 0
            val ip = intToIp(connInfo?.ipAddress ?: 0)
            val isUp = connInfo != null && connInfo.ipAddress != 0
            DualWifiLogger.v(TAG, "Status [wlan0]: up=$isUp, ip=$ip, ssid=$ssid, freq=${freq}MHz")
            InterfaceInfo(iface, isUp, ip?.takeIf { isUp }, ssid, freq)
        } else {
            // Secondary (wlan1): still needs root
            val linkRes = RootShell.run("ip -br link show $iface 2>/dev/null")
            val isUp = linkRes.output.contains("UP")
            val addrRes = RootShell.run("ip -br addr show $iface 2>/dev/null")
            val ip = Regex("""(\d+\.\d+\.\d+\.\d+)""").find(addrRes.output)?.value

            var ssid: String? = null
            var freq = 0
            val wpaRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i $iface status 2>/dev/null")
            if (wpaRes.isSuccess) {
                for (line in wpaRes.output.lines()) {
                    if (line.startsWith("ssid=")) ssid = line.removePrefix("ssid=").trim('"')
                    if (line.startsWith("freq=")) freq = line.removePrefix("freq=").toIntOrNull() ?: 0
                }
            }
            DualWifiLogger.v(TAG, "Status [$iface]: up=$isUp, ip=${ip ?: "none"}, ssid=${ssid ?: "none"}, freq=${freq}MHz")
            InterfaceInfo(iface, isUp, ip, ssid, freq)
        }
    }

    private fun intToIp(i: Int): String? {
        if (i == 0) return null
        return "${i and 0xff}.${(i shr 8) and 0xff}.${(i shr 16) and 0xff}.${(i shr 24) and 0xff}"
    }

    // ---------------------------------------------------------------------------
    // SLA status — reads kernel node and interface traffic statistics
    // ---------------------------------------------------------------------------
    suspend fun getSlaStatus(): SlaInfo = withContext(Dispatchers.IO) {
        val slaEnabledProp = getSystemProperty("vendor.sla.enabled", "0") == "1"

        val procConfig = RootShell.run("cat /proc/sla/config 2>/dev/null")
        val isEnabled = slaEnabledProp || procConfig.output.contains("enable=1")

        val psRes = RootShell.run("ps -ef | grep slad 2>/dev/null")
        val daemonRunning = psRes.output.contains("slad")

        val w0 = getInterfaceTraffic("wlan0")
        val w1 = getInterfaceTraffic("wlan1")

        DualWifiLogger.v(TAG, "SLA Status: enabled=$isEnabled, daemon=$daemonRunning, w0=$w0, w1=$w1")
        SlaInfo(isEnabled, daemonRunning, w0, w1)
    }

    private fun getInterfaceTraffic(iface: String): Long {
        try {
            val file = java.io.File("/proc/net/dev")
            if (file.exists()) {
                for (line in file.readLines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("$iface:")) {
                        val stats = trimmed.removePrefix("$iface:").trim().split(Regex("\\s+"))
                        if (stats.size >= 9) {
                            val rx = stats[0].toLongOrNull() ?: 0L
                            val tx = stats[8].toLongOrNull() ?: 0L
                            return rx + tx
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val rx = RootShell.run("cat /sys/class/net/$iface/statistics/rx_bytes 2>/dev/null").output.trim().toLongOrNull() ?: 0L
        val tx = RootShell.run("cat /sys/class/net/$iface/statistics/tx_bytes 2>/dev/null").output.trim().toLongOrNull() ?: 0L
        return rx + tx
    }

    // ---------------------------------------------------------------------------
    // Connect secondary — uses Multi-Internet mode + HyperFusion SLA
    // ---------------------------------------------------------------------------
    suspend fun connectSecondary(ssid: String, psk: String, logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Connecting secondary to '$ssid' via Multi-Internet mode...")
        logger("Enabling Dual Wi-Fi Multi-Internet mode...")

        // Enable Multi-STA concurrency (mode 2 = MCC, works without wlan1 spawning)
        val modeOk = enableMultiInternetMode(2)
        if (!modeOk) {
            DualWifiLogger.w(TAG, "Multi-Internet mode enable may have partially failed")
        }
        logger("Multi-Internet mode active. Connecting to $ssid...")

        // Use wpa_cli on wlan1 if available (root path)
        val addRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 add_network")
        val netId = addRes.output.trim().toIntOrNull()
        if (netId != null) {
            DualWifiLogger.d(TAG, "wlan1 wpa_cli path available, network id=$netId")
            logger("wpa_cli path: configuring network $netId on wlan1...")
            RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId ssid '\"$ssid\"'")
            if (psk.isNotEmpty()) {
                RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId psk '\"$psk\"'")
            } else {
                RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId key_mgmt NONE")
            }
            RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 enable_network $netId")
            logger("wlan1 authentication requested...")

            var connected = false
            for (i in 1..10) {
                kotlinx.coroutines.delay(1000)
                val stat = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 status")
                if (stat.output.contains("wpa_state=COMPLETED")) {
                    connected = true
                    logger("wlan1 associated!")
                    break
                }
                DualWifiLogger.v(TAG, "wlan1 poll $i/10: ${stat.output.lines().firstOrNull()}")
            }

            if (connected) {
                logger("Requesting DHCP on wlan1...")
                RootShell.run("dhcptool wlan1 || udhcpc -i wlan1 -n -q || dhclient wlan1")
                enableHyperFusion(true)
                logger("Dual Wi-Fi & SLA active!")
                return@withContext true
            }
        }

        // Fallback: framework handled it via Multi-Internet mode alone
        DualWifiLogger.i(TAG, "wpa_cli path not available — relying on framework Multi-Internet mode")
        logger("Framework Multi-Internet mode engaged. Android will manage the second AP connection.")
        enableHyperFusion(true)
        logger("HyperFusion SLA enabled.")
        return@withContext modeOk
    }

    suspend fun disconnectSecondary(logger: (String) -> Unit) = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Disconnecting secondary and disabling SLA...")
        logger("Disabling Dual Wi-Fi & SLA...")
        enableMultiInternetMode(0)
        enableHyperFusion(false)
        RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 disconnect 2>/dev/null")
        RootShell.run("ip rule del fwmark 0x5c lookup 1028 2>/dev/null")
        logger("Secondary interface disconnected.")
    }

    // ---------------------------------------------------------------------------
    // Self-test
    // ---------------------------------------------------------------------------
    suspend fun runSelfTest(): String = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "=== RUNNING SYSTEM SELF-TEST ===")
        val sb = StringBuilder()

        // 1. WifiManager scan results (no root)
        val scanResults = wifiManager.scanResults ?: emptyList()
        sb.appendLine("1. WifiManager.getScanResults(): ${scanResults.size} networks")
        scanResults.take(5).forEach { sb.appendLine("   ${it.SSID} ${it.frequency}MHz ${it.level}dBm") }

        // 2. Connected network (no root)
        @Suppress("DEPRECATION")
        val connInfo = wifiManager.connectionInfo
        sb.appendLine("2. Primary wlan0: ssid=${connInfo?.ssid} freq=${connInfo?.frequency}MHz ip=${intToIp(connInfo?.ipAddress ?: 0)}")

        // 3. Multi-Internet mode setting
        val multiMode = try {
            android.provider.Settings.Global.getInt(context.contentResolver, "wifi_multi_internet_mode", 0)
        } catch (_: Exception) { -1 }
        sb.appendLine("3. wifi_multi_internet_mode: $multiMode")

        // 4. SLA kernel node
        val slaRes = RootShell.run("cat /proc/sla/config 2>/dev/null")
        sb.appendLine("4. /proc/sla/config: ${if (slaRes.isSuccess) slaRes.output.lines().firstOrNull() else "No access (${slaRes.exitCode})"}")

        // 5. vendor.sla.enabled property
        val slaProp = getSystemProperty("vendor.sla.enabled", "0")
        sb.appendLine("5. vendor.sla.enabled: $slaProp")

        // 6. Root check
        val rootRes = RootShell.run("id")
        sb.appendLine("6. Root: exit=${rootRes.exitCode} → ${rootRes.output.lines().firstOrNull() ?: "no output"}")

        // 7. wlan1 status
        val w1Res = RootShell.run("ip -br link show wlan1 2>/dev/null")
        sb.appendLine("7. wlan1: ${if (w1Res.isSuccess && w1Res.output.isNotEmpty()) w1Res.output.trim() else "Not spawned (root exit=${w1Res.exitCode})"}")

        val report = sb.toString().trim()
        DualWifiLogger.i(TAG, "Self-Test:\n$report")
        report
    }
}
