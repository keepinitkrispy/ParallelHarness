package com.androidharness.app.data.env

import android.content.Context
import android.content.Intent

/**
 * One-shot installer trigger for the lightweight Termux -> Shizuku/rish bridge.
 *
 * This does not start the local model or the historical SolBridge worker. It
 * asks Termux to fetch and execute the private installer from solbridge-bus.
 */
object TermuxRishBootstrap {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    private const val HOME = "/data/data/com.termux/files/home"
    private const val PREFIX = "/data/data/com.termux/files/usr"

    private val bootstrapCommand = """
        set -e
        mkdir -p /data/data/com.termux/files/home/.cache
        /data/data/com.termux/files/usr/bin/gh api -H "Accept: application/vnd.github.raw+json" repos/keepinitkrispy/solbridge-bus/contents/runtime/bootstrap/install_rish_github_bridge.py > /data/data/com.termux/files/home/.cache/install_rish_github_bridge.py
        exec /data/data/com.termux/files/usr/bin/python /data/data/com.termux/files/home/.cache/install_rish_github_bridge.py
    """.trimIndent()

    fun launch(context: Context): Result<Unit> = runCatching {
        val intent = Intent()
            .setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            .setAction(ACTION_RUN_COMMAND)
            .putExtra(EXTRA_PATH, "$PREFIX/bin/bash")
            .putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", bootstrapCommand))
            .putExtra(EXTRA_WORKDIR, HOME)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_LABEL, "Parallel Rish bridge")
            .putExtra(
                EXTRA_DESCRIPTION,
                "Install the lightweight privileged command bridge without starting a local model.",
            )

        check(context.startService(intent) != null) {
            "Termux RunCommandService was not started"
        }
    }
}
