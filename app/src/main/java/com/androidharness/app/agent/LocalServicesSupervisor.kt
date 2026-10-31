package com.androidharness.app.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone owns the local Python/Claude delegation services.
 *
 * This supervisor runs on Android's WorkManager schedule. It restarts the
 * owner's already-installed Termux lab only when its health endpoint is down.
 * It never calls ChatGPT's remote tool gateway or treats Claude as an agent
 * with authority to determine objective, actions, or completion.
 */
internal object LocalServicesSupervisor {
    private const val TAG = "LocalServicesSupervisor"
    private const val HEALTH = "http://127.0.0.1:18766/api/health"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    private fun healthy(): Boolean = runCatching {
        val connection = URL(HEALTH).openConnection() as HttpURLConnection
        connection.connectTimeout = 1200
        connection.readTimeout = 1200
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK &&
                connection.inputStream.bufferedReader().use { reader ->
                    Regex("\"online\"\\s*:\\s*true").containsMatchIn(reader.readText())
                }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    suspend fun ensureStarted(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (healthy()) return@withContext true
        // Termux RunCommandService requires the user's previously granted
        // com.termux.permission.RUN_COMMAND and allow-external-apps=true.
        // No privileged prompt or new service installation is initiated.
        val command = Intent("com.termux.RUN_COMMAND")
            .setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            .putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("$TERMUX_HOME/start_execution_lab.sh"),
            )
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "ParallelHarness services")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
                "Start the local objective ledger and model delegation worker")
        runCatching { context.startService(command) }.onFailure {
            Log.w(TAG, "Termux service restart unavailable: ${it.javaClass.simpleName}")
        }
        repeat(4) {
            delay(1500)
            if (healthy()) return@withContext true
        }
        false
    }
}
