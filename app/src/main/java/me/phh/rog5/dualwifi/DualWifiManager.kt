package me.phh.rog5.dualwifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DualWifiManager {

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

    suspend fun getInterfaceStatus(iface: String): InterfaceInfo = withContext(Dispatchers.IO) {
        val linkRes = RootShell.run("ip -br link show $iface")
        val isUp = linkRes.output.contains("UP")

        val addrRes = RootShell.run("ip -br addr show $iface")
        val ipRegex = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        val ip = ipRegex.find(addrRes.output)?.value

        // Try querying wpa_cli if available
        var ssid: String? = null
        var freq = 0
        var speed = 0
        val wpaRes = RootShell.run("wpa_cli -p /data/vendor/wifi/wpa/sockets -i $iface status")
        if (wpaRes.isSuccess) {
            for (line in wpaRes.output.lines()) {
                if (line.startsWith("ssid=")) ssid = line.removePrefix("ssid=")
                if (line.startsWith("freq=")) freq = line.removePrefix("freq=").toIntOrNull() ?: 0
            }
        }

        InterfaceInfo(iface, isUp, ip, ssid, freq, speed)
    }

    suspend fun getSlaStatus(): SlaInfo = withContext(Dispatchers.IO) {
        val psRes = RootShell.run("ps -ef | grep slad")
        val daemonRunning = psRes.output.contains("slad")

        val procConfig = RootShell.run("cat /proc/sla/config 2>/dev/null")
        val isEnabled = procConfig.output.contains("enable=1")

        val w0 = RootShell.run("cat /proc/sla/wlan0_stats 2>/dev/null").output.toLongOrNull() ?: 0L
        val w1 = RootShell.run("cat /proc/sla/wlan1_stats 2>/dev/null").output.toLongOrNull() ?: 0L

        SlaInfo(isEnabled, daemonRunning, w0, w1)
    }

    suspend fun spawnWlan1(logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logger("Requesting wlan1 interface spawn via wificond...")
        // Call wificond createClientInterface for wlan1
        val res = RootShell.run("service call wifinl80211 1 s16 'wlan1'")
        logger("wificond response: ${res.output.lines().firstOrNull() ?: "Done"}")

        // Check if wlan1 appeared
        val check = RootShell.run("ip link show wlan1")
        val success = check.isSuccess && !check.output.contains("does not exist")
        if (success) {
            logger("wlan1 created successfully!")
            RootShell.run("ip link set dev wlan1 up")
        } else {
            logger("Notice: wlan1 was not spawned by wificond directly (${check.output.trim()}).")
        }
        success
    }

    suspend fun connectSecondary(ssid: String, psk: String, logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logger("Connecting secondary interface wlan1 to '$ssid'...")

        // Step 1: Ensure wlan1 is UP
        RootShell.run("ip link set dev wlan1 up")

        // Step 2: Configure wpa_supplicant
        val socketPath = "/data/vendor/wifi/wpa/sockets"
        val addRes = RootShell.run("wpa_cli -p $socketPath -i wlan1 add_network")
        val netId = addRes.output.trim().toIntOrNull() ?: 0
        logger("Configuring network index: $netId")

        RootShell.run("wpa_cli -p $socketPath -i wlan1 set_network $netId ssid '\"$ssid\"'")
        if (psk.isNotEmpty()) {
            RootShell.run("wpa_cli -p $socketPath -i wlan1 set_network $netId psk '\"$psk\"'")
        } else {
            RootShell.run("wpa_cli -p $socketPath -i wlan1 set_network $netId key_mgmt NONE")
        }
        RootShell.run("wpa_cli -p $socketPath -i wlan1 enable_network $netId")
        logger("Authentication requested for wlan1...")

        // Step 3: Wait for association
        var connected = false
        for (i in 1..10) {
            kotlinx.coroutines.delay(1000)
            val stat = RootShell.run("wpa_cli -p $socketPath -i wlan1 status")
            if (stat.output.contains("wpa_state=COMPLETED")) {
                connected = true
                logger("wlan1 associated successfully!")
                break
            }
        }

        if (!connected) {
            logger("Timed out waiting for wlan1 association.")
            return@withContext false
        }

        // Step 4: Request IP via DHCP
        logger("Requesting DHCP lease on wlan1...")
        RootShell.run("dhcptool wlan1 || udhcpc -i wlan1 -n -q || dhclient wlan1")

        // Step 5: Configure routing & SLA
        val addrRes = RootShell.run("ip -br addr show wlan1")
        val ipRegex = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        val ip = ipRegex.find(addrRes.output)?.value
        if (ip != null) {
            val subnetPrefix = ip.substringBeforeLast(".")
            val gateway = "$subnetPrefix.1"
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
            logger("Dual Wi-Fi & Gaming SLA is ACTIVE!")
            return@withContext true
        } else {
            logger("Failed to obtain IP address on wlan1.")
            return@withContext false
        }
    }

    suspend fun disconnectSecondary(logger: (String) -> Unit) = withContext(Dispatchers.IO) {
        logger("Disconnecting wlan1 and disabling SLA...")
        RootShell.run("wpa_cli -p /data/vendor/wifi/wpa/sockets -i wlan1 disconnect")
        RootShell.run("ip rule del fwmark 0x5c lookup 1028 2>/dev/null")
        RootShell.run("echo 'enable=0' > /proc/sla/config 2>/dev/null")
        RootShell.run("setprop vendor.sla.enabled 0")
        logger("Secondary interface disconnected.")
    }
}
