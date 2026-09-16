package me.phh.rog5.dualwifi

import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    fun run(cmd: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            val exitCode = process.waitFor()
            CommandResult(exitCode, output.toString().trim())
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: "Execution error")
        }
    }

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
