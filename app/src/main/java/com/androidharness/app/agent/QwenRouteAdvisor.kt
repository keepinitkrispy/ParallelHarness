package com.androidharness.app.agent

import com.androidharness.app.llm.ParallelDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional private, device-local Qwen consultation. The native coordinator
 * remains the authority on objective, constraints and tool receipts. Qwen is
 * an alternative-route advisor, not a mechanism for bypassing unavailable
 * permissions, provider boundaries or independent verification.
 *
 * Inference runs on the trained Qwen3-VL-8B Kaggle GPU, accessed only
 * through an authenticated Mac bridge/tunnel. The phone is never the model
 * execution machine. If the session/bridge is offline, the original
 * objective and native continuation remain intact.
 */
internal object QwenRouteAdvisor {
    private val URL_TEXT = ParallelDefaults.GPU_BRIDGE + "/chat/completions"

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://127.0.0.1:18080/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 450
            conn.readTimeout = 700
            try {
                if (conn.responseCode != 200) false else {
                    val health = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    health.optString("status") == "ok" &&
                        health.optString("model") == ParallelDefaults.LOCAL_MODEL &&
                        health.optBoolean("trained_adapter_loaded", false)
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) { false }
    }

    /** Advisory intake only; the owner's objective is never rewritten by it. */
    suspend fun auditPrimary(goal: String, primaryFrame: String, facts: List<RealityArbitration.Observation>): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", ParallelDefaults.LOCAL_MODEL)
            put("stream", false)
            put("temperature", 0.1)
            put("max_tokens", 210)
            put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You are a SECONDARY frame auditor, not a primary classifier or executive. " +
                    "ChatGPT/Claude already proposed an interpretation or route. Audit THEIR claims, " +
                    "against the user's literal objective and independent observations. " +
                    "Extract only specific route claims made by the PRIMARY model; do not fabricate exclusions " +
                    "or treat an unprobed route as impossible. An unusual route is NOT invalid. " +
                    "Return JSON with a short 'frame' description of the primary proposal, " +
                    "and 'route_claims' array of {route,status}, where status describes what the PRIMARY " +
                    "MODEL asserts: AVAILABLE_NOW, UNAVAILABLE_NOW, or UNKNOWN. " +
                    "Only name matching observed route IDs when truly supported; unknown routes stay unknown. " +
                    "Do not authorize or refuse actions; reality and the owner's intent are authoritative."))
                put(JSONObject().put("role", "user").put("content",
                    "Literal user objective:\n" + goal.take(1100) +
                    "\nPrimary-model proposal (classify its stated assumptions, do not adopt them):\n" +
                    primaryFrame.take(1500) +
                    "\nIndependently probed routes (limited scope; other routes remain UNKNOWN):\n" +
                    facts.joinToString("\n") { it.route + "=" + it.observed.name + " [" + it.source + "]" }.take(900)))
            })
        }
        complete(payload)
    }

    suspend fun suggest(goal: String, failure: String, unmet: List<String>): String? =
        withContext(Dispatchers.IO) {
            val requestBody = JSONObject().apply {
                put("model", ParallelDefaults.LOCAL_MODEL)
                put("stream", false)
                put("temperature", 0.25)
                put("max_tokens", 280)
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content",
                        "You are the user's local alternative-route analyst. Preserve the literal objective. " +
                        "Consider changing tool, interface, machine, architecture or sequence. " +
                        "Never invent successful executions, access, credentials, permission, or results. " +
                        "Do not advise bypassing a hard authorization boundary. " +
                        "Give a concise feasible NEXT ACTION, its dependencies, and a way to verify it. " +
                        "Prefer a materially different working capability over waiting or retrying the same exhausted provider."))
                    put(JSONObject().put("role", "user").put("content",
                        "Original objective:\n" + goal.take(2800) +
                        "\nPrevious attempt or refusal:\n" + failure.take(1100) +
                        "\nUnmet criteria:\n" + unmet.joinToString("; ").take(1200)))
                })
            }
            complete(requestBody)
        }

    private fun complete(requestBody: JSONObject): String? {
            try {
                val conn = URL(URL_TEXT).openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 12_000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                try {
                    conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) return null
                    val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    reply.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content")?.trim()?.takeIf { it.isNotBlank() }?.take(1800)
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                null // Offline local model is a route failure, not objective completion.
            }
    }
}
