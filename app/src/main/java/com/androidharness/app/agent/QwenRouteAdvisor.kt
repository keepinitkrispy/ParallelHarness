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
 * This becomes active when the owner runs the built-in local Qwen endpoint
 * (127.0.0.1:8080/v1). If Qwen is offline, the original objective and
 * native continuation still work without an invented answer.
 */
internal object QwenRouteAdvisor {
    private const val URL_TEXT = "http://127.0.0.1:8080/v1/chat/completions"

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://127.0.0.1:8080/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 450
            conn.readTimeout = 700
            try {
                conn.responseCode == 200 &&
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("status") == "ok"
            } finally { conn.disconnect() }
        } catch (_: Exception) { false }
    }

    /** Advisory intake only; the owner's objective is never rewritten by it. */
    suspend fun classify(goal: String, facts: List<RealityArbitration.Observation>): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", ParallelDefaults.LOCAL_MODEL)
            put("stream", false)
            put("temperature", 0.1)
            put("max_tokens", 210)
            put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You are a NON-BINDING possibility hypothesis generator. " +
                    "The user's literal objective and verified capability observations outrank your priors. " +
                    "Do not infer motive, classify emotion, or narrow the goal. " +
                    "Never exclude a route because it is unusual; unprobed is UNKNOWN, not impossible. " +
                    "Return JSON: {\"frame\":\"literal objective and first actionable route\",\"route_claims\":[{\"route\":\"local_qwen\",\"status\":\"AVAILABLE_NOW\"}]}. " +
                    "Only make route claims when there is evidence. Valid statuses AVAILABLE_NOW, UNAVAILABLE_NOW, UNKNOWN."))
                put(JSONObject().put("role", "user").put("content",
                    "Literal request:\n" + goal.take(1100) +
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
