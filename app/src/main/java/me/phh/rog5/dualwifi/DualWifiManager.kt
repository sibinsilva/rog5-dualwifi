package me.phh.rog5.dualwifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DualWifiManager {

    companion object {
        private const val TAG = "DualWifi_Manager"
        private const val SOCKET_PATH = "/data/vendor/wifi/wpa/sockets"
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

    suspend fun scanNetworks(): List<ScannedNetwork> = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Starting network scan...")
        val networks = mutableListOf<ScannedNetwork>()

        // 1. Trigger background scan via wpa_cli if socket exists
        val triggerRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan0 scan")
        DualWifiLogger.d(TAG, "wpa_cli scan trigger result: exit=${triggerRes.exitCode}, out=${triggerRes.output.trim()}")

        // Short pause for radio sweep
        kotlinx.coroutines.delay(1000)

        // 2. Query wpa_cli scan results
        val wpaRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan0 scan_results")
        if (wpaRes.isSuccess && wpaRes.output.contains("\t")) {
            var wpaCount = 0
            for (line in wpaRes.output.lines()) {
                val parts = line.split("\t")
                if (parts.size >= 5) {
                    val bssid = parts[0].trim()
                    val freq = parts[1].trim().toIntOrNull() ?: 0
                    val level = parts[2].trim().toIntOrNull() ?: 0
                    val flags = parts[3].trim()
                    val ssid = parts[4].trim()
                    if (ssid.isNotEmpty()) {
                        networks.add(ScannedNetwork(ssid, bssid, freq, level, flags))
                        wpaCount++
                    }
                }
            }
            DualWifiLogger.i(TAG, "wpa_cli scan_results yielded $wpaCount networks")
        } else {
            DualWifiLogger.w(TAG, "wpa_cli scan_results empty or failed (exit=${wpaRes.exitCode}): ${wpaRes.output.take(120)}")
        }

        // 3. Fallback / Augment: Query Android framework scan results via 'cmd wifi list-scan-results'
        val cmdRes = RootShell.run("cmd wifi list-scan-results")
        if (cmdRes.isSuccess && cmdRes.output.lines().size > 1) {
            var cmdCount = 0
            val bssidRegex = Regex("""([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")
            val flagsRegex = Regex("""(\[[^\]]+\](?:\[[^\]]+\])*)""")
            val rssiRegex = Regex("""(-?\d+)(?:\([^\)]*\))?""")

            for (line in cmdRes.output.lines()) {
                val bssidMatch = bssidRegex.find(line) ?: continue
                val bssid = bssidMatch.value

                val flagsMatch = flagsRegex.find(line)
                val flags = flagsMatch?.value ?: ""

                val beforeFlags = if (flagsMatch != null) line.substring(0, flagsMatch.range.first) else line
                val afterBssid = beforeFlags.substring(bssidMatch.range.last + 1).trim()
                val tokens = afterBssid.split(Regex("""\s+"""))

                if (tokens.size >= 3) {
                    val freq = tokens[0].toIntOrNull() ?: 0
                    val rssiStr = rssiRegex.find(tokens[1])?.groupValues?.get(1)
                    val level = rssiStr?.toIntOrNull() ?: -80

                    // SSID is whatever remains after age token
                    val ssidStartIndex = afterBssid.indexOf(tokens[2]) + tokens[2].length
                    val rawSsid = afterBssid.substring(ssidStartIndex).trim()

                    if (rawSsid.isNotEmpty() && !rawSsid.startsWith("[")) {
                        networks.add(ScannedNetwork(rawSsid, bssid, freq, level, flags))
                        cmdCount++
                    }
                }
            }
            DualWifiLogger.i(TAG, "cmd wifi list-scan-results yielded $cmdCount networks")
        }

        // Deduplicate by SSID + band, prioritizing 5 GHz and highest signal level
        val deduplicated = networks
            .distinctBy { "${it.ssid}_${it.is5GHz}" }
            .sortedWith(compareByDescending<ScannedNetwork> { it.is5GHz }.thenByDescending { it.level })

        val count5G = deduplicated.count { it.is5GHz }
        val count2G = deduplicated.count { !it.is5GHz }
        DualWifiLogger.i(TAG, "Scan completed: ${deduplicated.size} unique networks found ($count5G on 5 GHz DBS, $count2G on 2.4 GHz)")

        deduplicated
    }

    suspend fun getInterfaceStatus(iface: String): InterfaceInfo = withContext(Dispatchers.IO) {
        val linkRes = RootShell.run("ip -br link show $iface 2>/dev/null")
        val isUp = linkRes.output.contains("UP")

        val addrRes = RootShell.run("ip -br addr show $iface 2>/dev/null")
        val ipRegex = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        val ip = ipRegex.find(addrRes.output)?.value

        var ssid: String? = null
        var freq = 0
        var speed = 0

        // Query wpa_cli if socket exists
        val wpaRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i $iface status 2>/dev/null")
        if (wpaRes.isSuccess) {
            for (line in wpaRes.output.lines()) {
                if (line.startsWith("ssid=")) ssid = line.removePrefix("ssid=").trim('"')
                if (line.startsWith("freq=")) freq = line.removePrefix("freq=").toIntOrNull() ?: 0
            }
        }

        // Framework fallback if wpa_cli did not yield SSID for wlan0
        if (iface == "wlan0" && ssid.isNullOrEmpty()) {
            val cmdRes = RootShell.run("cmd wifi status 2>/dev/null")
            if (cmdRes.isSuccess) {
                val ssidMatch = Regex("""SSID:\s*\"?([^\",\n]+)\"?""").find(cmdRes.output)
                val freqMatch = Regex("""(24\d\d|5\d\d\d)\s*MHz""").find(cmdRes.output)
                ssid = ssidMatch?.groupValues?.get(1)?.trim()
                freq = freqMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }

        val info = InterfaceInfo(iface, isUp, ip, ssid, freq, speed)
        DualWifiLogger.v(TAG, "Status [$iface]: up=$isUp, ip=${ip ?: "none"}, ssid=${ssid ?: "none"}, freq=${freq}MHz")
        info
    }

    suspend fun getSlaStatus(): SlaInfo = withContext(Dispatchers.IO) {
        val psRes = RootShell.run("ps -ef | grep slad 2>/dev/null")
        val daemonRunning = psRes.output.contains("slad")

        val procConfig = RootShell.run("cat /proc/sla/config 2>/dev/null")
        val isEnabled = procConfig.output.contains("enable=1")

        val w0 = RootShell.run("cat /proc/sla/wlan0_stats 2>/dev/null").output.trim().toLongOrNull() ?: 0L
        val w1 = RootShell.run("cat /proc/sla/wlan1_stats 2>/dev/null").output.trim().toLongOrNull() ?: 0L

        DualWifiLogger.v(TAG, "SLA Status: enabled=$isEnabled, daemon=$daemonRunning, w0=$w0, w1=$w1")
        SlaInfo(isEnabled, daemonRunning, w0, w1)
    }

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

    suspend fun connectSecondary(ssid: String, psk: String, logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Connecting secondary interface wlan1 to '$ssid'...")
        logger("Connecting secondary interface wlan1 to '$ssid'...")

        // Step 1: Ensure wlan1 is UP
        RootShell.run("ip link set dev wlan1 up")

        // Step 2: Configure wpa_supplicant
        val addRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 add_network")
        val netId = addRes.output.trim().toIntOrNull() ?: 0
        DualWifiLogger.d(TAG, "wlan1 network index assigned: $netId")
        logger("Configuring network index: $netId")

        RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId ssid '\"$ssid\"'")
        if (psk.isNotEmpty()) {
            RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId psk '\"$psk\"'")
        } else {
            RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 set_network $netId key_mgmt NONE")
        }
        RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 enable_network $netId")
        logger("Authentication requested for wlan1...")

        // Step 3: Wait for association
        var connected = false
        for (i in 1..10) {
            kotlinx.coroutines.delay(1000)
            val stat = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 status")
            DualWifiLogger.v(TAG, "wlan1 poll $i/10: ${stat.output.lines().take(3).joinToString("; ")}")
            if (stat.output.contains("wpa_state=COMPLETED")) {
                connected = true
                DualWifiLogger.i(TAG, "wlan1 successfully associated!")
                logger("wlan1 associated successfully!")
                break
            }
        }

        if (!connected) {
            DualWifiLogger.w(TAG, "Timed out waiting for wlan1 association")
            logger("Timed out waiting for wlan1 association.")
            return@withContext false
        }

        // Step 4: Request IP via DHCP
        logger("Requesting DHCP lease on wlan1...")
        DualWifiLogger.i(TAG, "Requesting DHCP lease on wlan1...")
        RootShell.run("dhcptool wlan1 || udhcpc -i wlan1 -n -q || dhclient wlan1")

        // Step 5: Configure routing & SLA
        val addrRes = RootShell.run("ip -br addr show wlan1")
        val ipRegex = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        val ip = ipRegex.find(addrRes.output)?.value
        if (ip != null) {
            val subnetPrefix = ip.substringBeforeLast(".")
            val gateway = "$subnetPrefix.1"
            DualWifiLogger.i(TAG, "wlan1 obtained IP: $ip (Gateway: $gateway)")
            logger("wlan1 obtained IP: $ip (Gateway: $gateway)")

            // Setup policy routing table 1028 (Asus standard)
            logger("Configuring routing table 1028 and fwmark 0x5c...")
            RootShell.run("ip route add default via $gateway dev wlan1 table 1028 2>/dev/null || ip route change default via $gateway dev wlan1 table 1028")
            RootShell.run("ip rule add from $ip lookup 1028 2>/dev/null")
            RootShell.run("ip rule add fwmark 0x5c lookup 1028 2>/dev/null")

            // Activate Kernel SLA bonding
            logger("Enabling Qualcomm SLA kernel driver...")
            RootShell.run("echo 'enable=1' > /proc/sla/config 2>/dev/null")
            RootShell.run("setprop vendor.sla.enabled 1")
            RootShell.run("setprop persist.vendor.sla.enabled 1")
            DualWifiLogger.i(TAG, "Qualcomm SLA kernel bonding activated successfully")
            logger("Dual Wi-Fi & Gaming SLA is ACTIVE!")
            return@withContext true
        } else {
            DualWifiLogger.e(TAG, "Failed to obtain IP address on wlan1")
            logger("Failed to obtain IP address on wlan1.")
            return@withContext false
        }
    }

    suspend fun disconnectSecondary(logger: (String) -> Unit) = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Disconnecting secondary interface wlan1...")
        logger("Disconnecting wlan1 and disabling SLA...")
        RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 disconnect")
        RootShell.run("ip rule del fwmark 0x5c lookup 1028 2>/dev/null")
        RootShell.run("echo 'enable=0' > /proc/sla/config 2>/dev/null")
        RootShell.run("setprop vendor.sla.enabled 0")
        DualWifiLogger.i(TAG, "Secondary interface disconnected")
        logger("Secondary interface disconnected.")
    }

    suspend fun runSelfTest(): String = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "=== RUNNING SYSTEM SELF-TEST ===")
        val sb = StringBuilder()

        // 1. Root check
        val idRes = RootShell.run("id")
        sb.appendLine("1. Root: exit=${idRes.exitCode}, out=${idRes.output}")

        // 2. wpa_supplicant socket
        val pingRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan0 ping")
        sb.appendLine("2. wpa_supplicant Socket: exit=${pingRes.exitCode}, out=${pingRes.output}")

        // 3. Primary interface status
        val linkRes = RootShell.run("ip -br addr show wlan0")
        sb.appendLine("3. wlan0 Addr: ${linkRes.output.trim()}")

        // 4. Secondary interface status
        val w1Res = RootShell.run("ip -br link show wlan1")
        sb.appendLine("4. wlan1 Link: ${if (w1Res.output.isNotEmpty()) w1Res.output.trim() else "Not spawned"}")

        // 5. SLA Kernel Node
        val slaRes = RootShell.run("cat /proc/sla/config 2>/dev/null")
        sb.appendLine("5. Kernel /proc/sla/config: ${if (slaRes.output.isNotEmpty()) slaRes.output.trim() else "None"}")

        // 6. slad daemon
        val psRes = RootShell.run("ps -ef | grep slad | grep -v grep")
        sb.appendLine("6. slad Daemon: ${if (psRes.output.isNotEmpty()) psRes.output.trim() else "Stopped"}")

        val report = sb.toString().trim()
        DualWifiLogger.i(TAG, "Self-Test Results:\n$report")
        report
    }
}
