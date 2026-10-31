package com.androidharness.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A model may propose routes, but it cannot exclude a route by assertion.
 *
 * "Not checked" is UNKNOWN, not impossible. A negative observation describes
 * NOW only and must not be promoted to permanent impossibility. Model claims
 * never replace the owner's literal objective or native authority checks.
 */
internal object RealityArbitration {
    enum class Reachability { AVAILABLE_NOW, UNAVAILABLE_NOW, UNKNOWN }
    data class Observation(val route: String, val observed: Reachability, val source: String)
    data class Divergence(
        val route: String,
        val modelClaim: Reachability,
        val verifiedObservation: Reachability,
        val source: String,
    )
    data class Decision(
        val proposedFrame: String,
        val observations: List<Observation>,
        val divergences: List<Divergence>,
        val unverifiedExclusions: List<String>,
    ) {
        fun asJson(): JSONObject = JSONObject().apply {
            put("proposed_frame", proposedFrame.take(1400))
            put("observations", JSONArray(observations.map {
                JSONObject().put("route", it.route).put("status", it.observed.name)
                    .put("source", it.source)
            }))
            put("divergences", JSONArray(divergences.map {
                JSONObject().put("route", it.route).put("claim", it.modelClaim.name)
                    .put("verified", it.verifiedObservation.name).put("source", it.source)
            }))
            put("unverified_exclusions", JSONArray(unverifiedExclusions))
        }

        fun instructions(): String = buildString {
            append("\nREALITY ARBITRATION (the owner's literal objective is authoritative):\n")
            if (observations.isEmpty()) {
                append("No concrete routes probed yet. Do not treat missing evidence as unavailable.\n")
            }
            observations.forEach {
                append(it.route).append(": ").append(it.observed.name)
                    .append(" [").append(it.source).append("]\n")
            }
            if (divergences.isNotEmpty()) {
                append("MODEL/REALITY DIVERGENCES — observation wins; log for training:\n")
                divergences.forEach {
                    append("- ").append(it.route).append(": proposed ")
                        .append(it.modelClaim.name).append(", observed ")
                        .append(it.verifiedObservation.name).append("\n")
                }
            }
            if (unverifiedExclusions.isNotEmpty()) {
                append("UNVERIFIED EXCLUSIONS — NOT BINDING: ")
                    .append(unverifiedExclusions.joinToString(", ")).append("\n")
            }
            if (proposedFrame.isNotBlank()) {
                append("Qwen's proposed interpretation (hypothesis, not a rule): ")
                    .append(proposedFrame.take(1000)).append("\n")
            }
            append("Inspect a materially different route before declaring a generic blocker. ")
            append("Only a specific verified constraint can exclude a specific operation; ")
            append("it cannot establish that every route is impossible. ")
            append("Continue toward the original objective and verify actual completion.\n")
        }
    }

    /**
     * Qwen's output is advisory JSON, with optional route_claims:
     * {"frame":"...","route_claims":[{"route":"local_qwen","status":"UNAVAILABLE_NOW"}]}.
     * If invalid, keep the raw text as an unstructured proposal without exclusions.
     */
    fun arbitrate(proposal: String?, observations: List<Observation>): Decision {
        val raw = proposal.orEmpty().trim()
        val parsed = runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(raw.substring(start, end + 1)) else null
        }.getOrNull()
        val frame = parsed?.optString("frame")?.takeIf { it.isNotBlank() } ?: raw.take(1400)
        val observed = observations.associateBy { it.route.lowercase() }
        val claims = parsed?.optJSONArray("route_claims") ?: JSONArray()
        val divergences = mutableListOf<Divergence>()
        val unknowns = mutableListOf<String>()
        for (index in 0 until claims.length()) {
            val claim = claims.optJSONObject(index) ?: continue
            val route = claim.optString("route").lowercase().take(120)
            if (route.isBlank()) continue
            val desired = runCatching {
                Reachability.valueOf(claim.optString("status"))
            }.getOrNull() ?: continue
            val fact = observed[route]
            when {
                desired == Reachability.UNAVAILABLE_NOW && fact == null ->
                    unknowns.add(route)
                fact != null && fact.observed != Reachability.UNKNOWN &&
                    desired != Reachability.UNKNOWN && desired != fact.observed ->
                    divergences.add(Divergence(route, desired, fact.observed, fact.source))
            }
        }
        return Decision(frame, observations, divergences, unknowns.distinct())
    }
}
