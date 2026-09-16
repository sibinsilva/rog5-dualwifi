package me.phh.rog5.dualwifi

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object RootShell {
    private const val TAG = "DualWifi_Root"

    private val suBinary: String by lazy {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "su"
        )
        for (path in candidates) {
            try {
                val proc = ProcessBuilder(path, "-v").start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                if (exit == 0) {
                    DualWifiLogger.i(TAG, "Found responsive su binary at '$path' (version: $out)")
                    return@lazy path
                }
            } catch (e: Exception) {
                DualWifiLogger.v(TAG, "Probing '$path': not responsive (${e.message})")
            }
        }
        DualWifiLogger.w(TAG, "No su candidate verified via '-v', defaulting to '/system/bin/su'")
        "/system/bin/su"
    }

    fun isRootAvailable(): Boolean {
        val res = run("id")
        val available = res.isSuccess && (res.output.contains("uid=0") || res.output.contains("root"))
        DualWifiLogger.i(TAG, "Root availability check: available=$available (exitCode=${res.exitCode}, out=${res.output.lines().firstOrNull()})")
        return available
    }

    fun run(cmd: String): CommandResult {
        val startTime = System.currentTimeMillis()
        DualWifiLogger.d(TAG, "CMD >>> $cmd")

        // Try mount-master first (-M), fallback to standard shell if -M is rejected
        val attempts = listOf(
            listOf(suBinary, "-M"),
            listOf(suBinary)
        )

        for ((index, cmdPrefix) in attempts.withIndex()) {
            try {
                val process = ProcessBuilder(cmdPrefix)
                    .redirectErrorStream(true)
                    .start()

                OutputStreamWriter(process.outputStream).use { writer ->
                    writer.write(cmd)
                    writer.write("\n")
                    writer.write("exit\n")
                    writer.flush()
                }

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }

                val exitCode = process.waitFor()
                val duration = System.currentTimeMillis() - startTime
                val trimmedOut = output.toString().trim()

                if (exitCode == 0) {
                    DualWifiLogger.d(TAG, "CMD <<< [OK ${duration}ms] (prefix=${cmdPrefix.joinToString(" ")})")
                    if (trimmedOut.isNotEmpty()) {
                        for (l in trimmedOut.lines().take(5)) {
                            DualWifiLogger.v(TAG, "  | $l")
                        }
                    }
                    return CommandResult(exitCode, trimmedOut)
                } else {
                    DualWifiLogger.w(TAG, "CMD <<< [FAIL exit=$exitCode in ${duration}ms] output: $trimmedOut")
                    if (index == 0 && trimmedOut.contains("invalid option -- M")) {
                        DualWifiLogger.d(TAG, "-M not supported by this su binary, falling back to standard su")
                        continue
                    }
                    return CommandResult(exitCode, trimmedOut)
                }
            } catch (e: Exception) {
                DualWifiLogger.w(TAG, "Attempt with ${cmdPrefix.joinToString(" ")} failed: ${e.message}")
                if (index < attempts.size - 1) continue
                val duration = System.currentTimeMillis() - startTime
                DualWifiLogger.e(TAG, "Execution exception on '$cmd' after ${duration}ms", e)
                return CommandResult(-1, e.message ?: "Execution exception")
            }
        }

        return CommandResult(-1, "Unable to execute root command")
    }

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
