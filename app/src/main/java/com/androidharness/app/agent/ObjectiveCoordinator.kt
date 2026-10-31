package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Native, durable authority for user action tasks. The app writes receipts
 * outside of model text, and verifies successful tool outcomes before finish.
 * This component is present even when the local MCP/lab service is down.
 */
class ObjectiveCoordinator(context: Context) {
    private val folder = File(context.filesDir, "objective-coordinator").apply { mkdirs() }
    private val active = ConcurrentHashMap<String, JSONObject>()
    private val lock = Any()
    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun path(sessionId: String) = File(folder, hash(sessionId).take(32) + ".json")
    private fun save(sessionId: String, value: JSONObject) = synchronized(lock) {
        val destination = path(sessionId)
        val tmp = File(folder, destination.name + ".tmp")
        tmp.writeText(value.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(destination)) {
            destination.writeText(value.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        active[sessionId] = value
    }
    private fun load(sessionId: String): JSONObject? = synchronized(lock) {
        active[sessionId] ?: path(sessionId).takeIf { it.isFile }?.let {
            runCatching { JSONObject(it.readText()) }.getOrNull()?.also { obj -> active[sessionId] = obj }
        }
    }
    private fun actionIntent(text: String): Boolean {
        // Conversational language drives actual work, not a command syntax.
        val verbs = listOf("build", "fix", "repair", "implement", "execute", "deploy",
            "configure", "connect", "automate", "install", "launch", "run", "create",
            "make", "send", "book", "publish", "get it", "get this", "get the",
            "finish", "continue", "resume", "update", "integrate", "start",
            "check and fix", "do that", "do it")
        var instruction = text.trim().lowercase()
        val prefixes = listOf("please ", "can you ", "could you ", "would you ",
            "will you ", "i want you to ", "i need you to ", "go ahead and ",
            "you should ", "i'd like you to ")
        repeat(3) {
            prefixes.firstOrNull(instruction::startsWith)?.let {
                instruction = instruction.removePrefix(it).trimStart()
            }
        }
        return verbs.any { instruction == it || instruction.startsWith("$it ") }
    }
    private fun remoteContext(): Pair<String, String> {
        return try {
            val connection = URL("http://127.0.0.1:18766/api/state")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1500
            try {
                val state = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val objectives = state.optJSONArray("objectives") ?: JSONArray()
                var objective = ""
                for (i in 0 until objectives.length()) {
                    val item = objectives.optJSONObject(i) ?: continue
                    if (item.optString("status") == "active") {
                        objective = item.optString("target").take(1400) + "\nAcceptance: " +
                            item.optString("acceptance").take(1000)
                        break
                    }
                }
                objective to state.optString("operating_contract").take(2000)
            } finally { connection.disconnect() }
        } catch (_: Exception) { "" to "" }
    }
    suspend fun begin(sessionId: String, turnId: String, request: String, resume: Boolean): String =
        withContext(Dispatchers.IO) {
            val previous = if (resume) load(sessionId)?.takeIf { it.optString("turn_id") == turnId } else null
            val state = previous ?: JSONObject().apply {
                put("session_id", sessionId)
                put("turn_id", turnId)
                put("goal", request)
                put("action_task", actionIntent(request))
                put("status", "running")
                put("nudges", 0)
                put("created", System.currentTimeMillis())
                put("tools", JSONArray())
            }
            val (objective, contract) = remoteContext()
            if (objective.isNotEmpty()) state.put("overarching", objective)
            if (contract.isNotEmpty()) state.put("operating_contract", contract)
            save(sessionId, state)
            "NATIVE EXECUTION COORDINATOR:\nUser's current objective: " +
                state.optString("goal") + "\nControlling objective: " +
                state.optString("overarching", "Preserve the current user's literal request") +
                "\n" + state.optString("operating_contract") +
                "\nThe native coordinator owns the objective, tool choices and verification. Claude is a passive reasoning source; never assign its model conversation executive authority. Keep working independently after the external ChatGPT conversation closes. Act using native tools. Delegate independent reasoning silently when useful. " +
                "For action tasks, independently verify outcome using a tool and call objective_finish with " +
                "actual successful tool-call ids and a concise result. The coordinator verifies receipts. " +
                "Do not claim completion from plans, model statements or unverified actions. " +
                "Only direct conflicts with the goal or refusals need the owner. Do not ask for routine approval."
        }
    suspend fun before(sessionId: String, callId: String, tool: String, arguments: String, isReadOnly: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val state = load(sessionId) ?: return@synchronized "No active native execution objective; tool call blocked"
                if (state.optString("status") != "running") return@synchronized "Objective is not running: " + state.optString("status")
                // Real modifying tools require independent verification even if
                // the user phrased the instruction as a question.
                if (!isReadOnly && tool != "objective_finish" && tool != "task" &&
                    !tool.startsWith("mcp__execution_lab__")) {
                    state.put("action_task", true)
                }
                state.getJSONArray("tools").put(JSONObject().apply {
                    put("id", callId)
                    put("name", tool)
                    put("args_hash", hash(arguments))
                    put("read_only", isReadOnly)
                    put("started", System.currentTimeMillis())
                    put("status", "started")
                })
                save(sessionId, state)
                null
            }
        }
    suspend fun after(sessionId: String, callId: String, result: ToolResult) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val state = load(sessionId) ?: return@synchronized
                val events = state.getJSONArray("tools")
                for (i in events.length() - 1 downTo 0) {
                    val item = events.optJSONObject(i) ?: continue
                    if (item.optString("id") == callId && item.optString("status") == "started") {
                        item.put("status", if (result.ok) "succeeded" else "failed")
                        item.put("output_sha256", hash(result.output))
                        item.put("output_excerpt", result.output.take(200))
                        item.put("finished", System.currentTimeMillis())
                        if (item.optString("name") == "objective_finish" && !result.ok) {
                            val failures = state.optInt("finish_failures", 0) + 1
                            state.put("finish_failures", failures)
                            if (failures >= 4) state.put("status", "unverified")
                        }
                        break
                    }
                }
                save(sessionId, state)
            }
        }
    suspend fun onModelStop(sessionId: String, response: String): String? =
        withContext(Dispatchers.IO) {
            val state = load(sessionId) ?: return@withContext null
            if (!state.optBoolean("action_task") || state.optString("status") == "completed")
                return@withContext null
            if (Regex("\\b(cannot|can't|refuse|blocked|not permitted|not authorized)\\b",
                    RegexOption.IGNORE_CASE).containsMatchIn(response)) {
                state.put("status", "refused")
                state.put("last_reply", response.take(600))
                save(sessionId, state)
                return@withContext null
            }
            val nudges = state.optInt("nudges")
            if (nudges >= 8) {
                state.put("status", "unverified")
                save(sessionId, state)
                return@withContext null
            }
            state.put("nudges", nudges + 1)
            save(sessionId, state)
            val events = state.getJSONArray("tools")
            val successful = (0 until events.length()).count {
                events.optJSONObject(it)?.optString("status") == "succeeded"
            }
            if (successful == 0) {
                "The requested action has no successful tool execution yet. Work toward the literal outcome " +
                "using tools now. A plan or explanation does not complete the task."
            } else {
                "Execution has occurred but the task is not verified as completed. Inspect the actual result " +
                "and call objective_finish with result and evidence_call_ids: []; native receipts supply the IDs. Report a genuine blocker otherwise."
            }
        }
    suspend fun complete(sessionId: String, evidence: List<String>, result: String): ToolResult =
        withContext(Dispatchers.IO) {
            val state = load(sessionId) ?: return@withContext ToolResult(false, "No task exists")
            if (state.optString("status") != "running")
                return@withContext ToolResult(false, "Task not in running state")
            val events = state.getJSONArray("tools")
            val successful = mutableListOf<Pair<String, String>>()
            val inspected = mutableListOf<Pair<String, String>>()
            var latestMutationIndex = -1
            val verificationIndices = mutableListOf<Int>()
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("status") != "succeeded") continue
                val name = event.optString("name")
                if (name == "objective_finish") continue
                val id = event.optString("id")
                successful.add(id to name)
                if (!event.optBoolean("read_only", false)) latestMutationIndex = i
                val check = name.startsWith("read_") || name.startsWith("browser_get") ||
                    name.startsWith("browser_snapshot") || name.startsWith("git_status") ||
                    name.startsWith("git_diff") || name.startsWith("mcp__execution_lab__delegation_result")
                if (check && event.optBoolean("read_only", false)) {
                    inspected.add(id to event.optString("output_excerpt"))
                    verificationIndices.add(i)
                }
            }
            val successfulIds = successful.map { it.first }.toSet()
            if (result.isBlank() || successfulIds.isEmpty())
                return@withContext ToolResult(false, "No independent successful execution evidence exists")
            val chosen = if (evidence.isNotEmpty() && evidence.all { it in successfulIds }) evidence
                         else successful.map { it.first }
            if (state.optBoolean("action_task")) {
                if (latestMutationIndex < 0)
                    return@withContext ToolResult(false, "Action has no successful native execution receipt")
                if (verificationIndices.none { it > latestMutationIndex })
                    return@withContext ToolResult(false, "Action requires independent read-back after its last successful modification")
            }
            val exact = Regex("(?i)containing exactly\\s+([A-Za-z0-9_-]+)")
                .find(state.optString("goal"))?.groupValues?.getOrNull(1)
            if (exact != null && inspected.none { it.second.contains(exact) })
                return@withContext ToolResult(false, "Read-back does not contain the exact requested content")
            state.put("status", "completed")
            state.put("evidence", JSONArray(chosen))
            state.put("result", result)
            state.put("completed", System.currentTimeMillis())
            save(sessionId, state)
            ToolResult(true, "Native task receipt recorded for " + chosen.size + " successful tool(s), with independent verification. " + result)
        }
    fun completionTool(): Tool = object : Tool {
        override val name = "objective_finish"
        override val description = "Complete the current task using native independently observed tool receipts. Supply result; evidence_call_ids may be omitted or empty; opaque call IDs are selected automatically from the native journal."
        override val isReadOnly = false
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("evidence_call_ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("result") { put("type", "string") }
            }
            putJsonArray("required") { add("result") }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val sid = ctx.sessionId ?: return ToolResult(false, "Session unavailable")
            val evidence = args["evidence_call_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            return complete(sid, evidence, args["result"]?.jsonPrimitive?.content.orEmpty())
        }
    }
    fun completionRequired(sessionId: String): Boolean = load(sessionId)?.optBoolean("action_task") == true
    fun status(sessionId: String): String? = load(sessionId)?.optString("status")
}