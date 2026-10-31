package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.tools.SecretRedactor
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
class ObjectiveCoordinator(
    private val folder: File,
    private val readLabState: Boolean = true,
) {
    constructor(context: Context) : this(File(context.filesDir, "objective-coordinator"))
    init { folder.mkdirs() }
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
    private fun constraintsOf(state: JSONObject): Set<ObjectivePolicy.Constraint> {
        val names = state.optJSONArray("constraints") ?: return emptySet()
        return (0 until names.length()).mapNotNull { i ->
            runCatching { ObjectivePolicy.Constraint.valueOf(names.optString(i)) }.getOrNull()
        }.toSet()
    }
    private fun journalOf(state: JSONObject): List<ObjectivePolicy.Event> {
        val events = state.optJSONArray("tools") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            val e = events.optJSONObject(i) ?: return@mapNotNull null
            if (e.optString("status") == "denied") return@mapNotNull null
            ObjectivePolicy.Event(
                id = e.optString("id"), name = e.optString("name"),
                args = e.optString("args_excerpt"), readOnly = e.optBoolean("read_only"),
                ok = e.optString("status") == "succeeded", output = e.optString("output_excerpt"),
            )
        }
    }
    private fun specOf(state: JSONObject) = ObjectivePolicy.Spec(
        goal = state.optString("goal"),
        constraints = constraintsOf(state),
        actionTask = state.optBoolean("action_task"),
    )
    private fun remoteContext(): Pair<String, String> {
        if (!readLabState) return "" to ""
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
    /**
     * Opens the run's objective. The owner's objective is durable across
     * turns: a bare "continue" inherits it, a further instruction amends it
     * while it is unfinished, and constraints accumulate for the whole session.
     * Only completion or an explicit reset ("new task: ...") starts over.
     */
    suspend fun begin(sessionId: String, turnId: String, request: String, resume: Boolean): String =
        withContext(Dispatchers.IO) {
            val prior = load(sessionId)
            val state = if (resume && prior?.optString("turn_id") == turnId) prior!! else {
                val newConstraints = ObjectivePolicy.constraints(request)
                val sessionConstraints = (prior?.let { constraintsOf(it) } ?: emptySet()) + newConstraints
                val unfinished = prior != null && prior.optString("status") != "completed" &&
                    prior.optString("goal").isNotBlank()
                val continuing = prior != null && prior.optString("goal").isNotBlank() &&
                    ObjectivePolicy.isContinuation(request)
                val amending = unfinished && !continuing && !ObjectivePolicy.isExplicitReset(request) &&
                    prior!!.optBoolean("action_task")
                when {
                    continuing || amending -> JSONObject(prior!!.toString()).apply {
                        put("turn_id", turnId)
                        if (amending) {
                            if (!has("root_goal")) put("root_goal", optString("goal"))
                            optJSONArray("amendments")?.put(request.take(4000))
                                ?: put("amendments", JSONArray().put(request.take(4000)))
                            put("goal", optString("root_goal", optString("goal")) + "\n\nOwner follow-up instructions (all still binding):\n" +
                                (0 until getJSONArray("amendments").length()).joinToString("\n") { "- " + getJSONArray("amendments").optString(it) })
                            if (actionIntent(request)) put("action_task", true)
                        }
                        put("status", "running")
                        put("nudges", 0)
                        put("finish_failures", 0)
                        remove("last_unmet")
                    }
                    else -> JSONObject().apply {
                        put("session_id", sessionId)
                        put("turn_id", turnId)
                        put("root_goal", request.take(8000))
                        put("goal", request.take(8000))
                        put("amendments", JSONArray())
                        put("action_task", actionIntent(request))
                        put("status", "running")
                        put("nudges", 0)
                        put("created", System.currentTimeMillis())
                        put("tools", JSONArray())
                    }
                }.apply {
                    put("constraints", JSONArray(sessionConstraints.map { it.name }))
                }
            }
            val (objective, contract) = remoteContext()
            // Observe known capabilities BEFORE inviting any model to propose a
            // frame. We only check the concrete routes we can probe now: every
            // other route remains UNKNOWN, never ruled out by typicality.
            val facts = mutableListOf<RealityArbitration.Observation>()
            if (readLabState) {
                val qwenReady = QwenRouteAdvisor.isAvailable()
                facts.add(RealityArbitration.Observation(
                    "local_qwen",
                    if (qwenReady) RealityArbitration.Reachability.AVAILABLE_NOW
                    else RealityArbitration.Reachability.UNAVAILABLE_NOW,
                    "127.0.0.1:8080/health (current health only)"))
            }
            val toolHistory = state.optJSONArray("tools") ?: JSONArray()
            for (i in (toolHistory.length() - 1) downTo maxOf(0, toolHistory.length() - 12)) {
                val entry = toolHistory.optJSONObject(i) ?: continue
                val status = entry.optString("status")
                if (status == "succeeded" || status == "denied") {
                    // A denial belongs to the exact attempted operation,
                    // never the whole tool: another argument can be authorized.
                    val route = "tool:" + entry.optString("name").lowercase() +
                        if (status == "denied") ":" + entry.optString("args_hash").take(16)
                        else ""
                    facts.add(RealityArbitration.Observation(
                        route,
                        if (status == "succeeded") RealityArbitration.Reachability.AVAILABLE_NOW
                        else RealityArbitration.Reachability.UNAVAILABLE_NOW,
                        "native tool-call journal, " + status + " (exact invocation; scope: this objective)"))
                }
            }
            // Qwen's assessment cannot authorize exclusions or rewrite the goal.
            // An unavailable Qwen also must not terminate or block the task.
            val qwenReady = facts.any { it.route == "local_qwen" &&
                it.observed == RealityArbitration.Reachability.AVAILABLE_NOW }
            val proposal = if (!resume && qwenReady && request.isNotBlank()) {
                QwenRouteAdvisor.classify(state.optString("goal"), facts)
            } else if (resume) state.optString("qwen_intake") else null
            val arbitration = RealityArbitration.arbitrate(proposal, facts)
            state.put("reality_arbitration", arbitration.asJson())
            if (proposal != null) {
                state.put("qwen_intake", proposal.take(1400))
                state.put("qwen_intake_at", System.currentTimeMillis())
            }
            if (objective.isNotEmpty()) state.put("overarching", objective)
            if (contract.isNotEmpty()) state.put("operating_contract", contract)
            save(sessionId, state)
            val spec = specOf(state)
            val enforced = buildString {
                if (spec.constraints.isNotEmpty()) {
                    append("\nOwner constraints enforced natively at every tool call (calls that violate them are denied):\n")
                    spec.constraints.forEach { append("- ").append(it.label).append('\n') }
                }
                if (spec.actionTask) {
                    append("\nCompletion is accepted only from journaled evidence that concerns this objective")
                    if (spec.stages.isNotEmpty()) append("; required stages, in order: ")
                        .append(spec.stages.joinToString(" -> ") { it.label })
                    if (spec.targets.isNotEmpty()) append("; named targets: ").append(spec.targets.joinToString(", "))
                    append(". A successful substitute or smaller task does not complete it.\n")
                }
            }
            "NATIVE EXECUTION COORDINATOR:\nGoverning objective (persists across turns until verified complete):\n" +
                state.optString("goal") + "\nControlling objective: " +
                state.optString("overarching", "Preserve the owner's literal request") +
                "\n" + state.optString("operating_contract") + enforced +
                arbitration.instructions() +
                "\nThe native coordinator owns the objective, tool choices and verification. Claude is a passive reasoning source; never assign its model conversation executive authority. Keep working independently after the external ChatGPT conversation closes. Act using native tools. Delegate independent reasoning silently when useful. " +
                "For action tasks, independently verify outcome using a tool and call objective_finish with " +
                "a concise result; native receipts supply the evidence. " +
                "Do not claim completion from plans, model statements or unverified actions. " +
                "Only owner-authority blockers (credentials, payment, consent, physical action, permission grants) need the owner. Do not ask for routine approval."
        }
    suspend fun before(sessionId: String, callId: String, tool: String, arguments: String, isReadOnly: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val state = load(sessionId) ?: return@synchronized "No active native execution objective; tool call blocked"
                if (state.optString("status") != "running") return@synchronized "Objective is not running: " + state.optString("status")
                // The owner's constraints decide tool admission, not the model.
                val denied = ObjectivePolicy.admit(tool, arguments, isReadOnly, constraintsOf(state), journalOf(state))
                // Real modifying tools require independent verification even if
                // the user phrased the instruction as a question.
                if (denied == null && !isReadOnly && tool != "objective_finish" && tool != "task" &&
                    !tool.startsWith("mcp__execution_lab__") &&
                    ObjectivePolicy.classify(tool, arguments, isReadOnly) != ObjectivePolicy.Kind.BOOKKEEPING) {
                    state.put("action_task", true)
                }
                val tools = state.getJSONArray("tools")
                tools.put(JSONObject().apply {
                    put("id", callId)
                    put("name", tool)
                    put("args_hash", hash(arguments))
                    put("args_excerpt", SecretRedactor.redact(arguments.take(1200)))
                    put("read_only", isReadOnly)
                    put("started", System.currentTimeMillis())
                    put("status", if (denied == null) "started" else "denied")
                    if (denied != null) put("denial", denied)
                })
                while (tools.length() > MAX_JOURNAL) tools.remove(0)
                save(sessionId, state)
                denied
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
                        item.put("output_excerpt", SecretRedactor.redact(result.output.take(600)))
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
            if (!state.optBoolean("action_task") || state.optString("status") != "running")
                return@withContext null
            val constraints = constraintsOf(state)
            val verdict = ObjectivePolicy.onStop(response, constraints)
            if (verdict is ObjectivePolicy.StopVerdict.Allow) {
                state.put("status", verdict.status)
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
            val unmet = ObjectivePolicy.unmet(specOf(state), journalOf(state), "pending")
            state.put("last_unmet", unmet.joinToString("; "))
            save(sessionId, state)
            val advice = QwenRouteAdvisor.suggest(state.optString("goal"), response, unmet)
            if (advice != null) {
                state.put("last_qwen_route", advice.take(1800))
                state.put("last_qwen_consulted", System.currentTimeMillis())
                save(sessionId, state)
            }
            val lead = (verdict as? ObjectivePolicy.StopVerdict.Nudge)?.message?.plus("\n").orEmpty()
            val route = if (advice != null) {
                "Local Qwen alternate-route proposal (not permission or proof of execution):\n" + advice + "\n"
            } else {
                "Qwen route advisor is currently offline. Keep pursuing another available route; do not treat that as the owner's task being finished.\n"
            }
            lead + route + if (unmet.isNotEmpty()) {
                "The owner's objective is not complete. Unmet, from the native journal:\n- " + unmet.joinToString("\n- ") +
                "\nContinue working on the original objective with tools now. A plan, explanation or substitute task does not complete it."
            } else {
                "Execution evidence now covers the objective. Inspect the actual result and call objective_finish with " +
                "result; native receipts supply the IDs. Report a genuine owner-authority blocker otherwise."
            }
        }
    suspend fun complete(sessionId: String, evidence: List<String>, result: String): ToolResult =
        withContext(Dispatchers.IO) {
            val state = load(sessionId) ?: return@withContext ToolResult(false, "No task exists")
            if (state.optString("status") != "running")
                return@withContext ToolResult(false, "Task not in running state")
            val journal = journalOf(state)
            val unmet = ObjectivePolicy.unmet(specOf(state), journal, result)
            if (unmet.isNotEmpty()) {
                state.put("last_unmet", unmet.joinToString("; "))
                save(sessionId, state)
                return@withContext ToolResult(false, "Completion rejected by the native coordinator:\n- " + unmet.joinToString("\n- "))
            }
            val successfulIds = journal.filter { it.ok && it.name != "objective_finish" }.map { it.id }
            val chosen = if (evidence.isNotEmpty() && evidence.all { it in successfulIds }) evidence else successfulIds
            state.put("status", "completed")
            state.put("evidence", JSONArray(chosen))
            state.put("result", result)
            state.remove("last_unmet")
            state.put("completed", System.currentTimeMillis())
            save(sessionId, state)
            ToolResult(true, "Native task receipt recorded for " + chosen.size + " successful tool(s), bound to the owner's objective with independent verification. " + result)
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
    /** Why the objective is not yet complete, for the run's paused reason. */
    fun unmetSummary(sessionId: String): String? = load(sessionId)?.optString("last_unmet")?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_JOURNAL = 400
    }
}
