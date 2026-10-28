package com.androidharness.app.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Integration boundary for the externally anchored CSRE authority service.
 *
 * Implementations must obtain decisions from the frozen signed-chain authority,
 * not from an LLM, a source label, the app's PermissionMode, or a remembered tool grant.
 * Neither signatures nor signing keys belong in this adapter.
 */
interface CsreAuthority {
    suspend fun decide(request: CsreToolRequest): CsreToolDecision
    suspend fun record(event: CsreToolEvent)
}

data class CsreToolRequest(
    val sessionId: String?,
    val operation: String,
    val resource: String,
    val detailsJson: String,
)

sealed interface CsreToolDecision {
    data object Allow : CsreToolDecision
    data class Deny(val reason: String) : CsreToolDecision
}

data class CsreToolEvent(
    val request: CsreToolRequest,
    val outcome: String,
    val detail: String,
)

/**
 * Wraps *all* registered tools, including run-scoped MCP and subagent tools.
 * There is no exception for FULL_ACCESS, FULL_AUTO, or isReadOnly metadata.
 * Only a CSRE Allow for the exact structured invocation reaches the action.
 */
internal class CsreGatedTool(
    private val delegate: Tool,
    private val authority: CsreAuthority,
) : Tool by delegate {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val request = CsreToolRequest(
            sessionId = ctx.sessionId,
            operation = delegate.name,
            resource = ctx.workspace.displayPath,
            detailsJson = args.toString(),
        )
        val decision = try {
            authority.decide(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(false, "CSRE authority unavailable; no tool executed.")
        }
        if (decision !is CsreToolDecision.Allow) {
            val reason = (decision as CsreToolDecision.Deny).reason
            try {
                authority.record(CsreToolEvent(request, "blocked", reason))
            } catch (_: Exception) {
                return ToolResult(false, "CSRE denied this operation; audit recording unavailable.")
            }
            return ToolResult(false, "CSRE denied this operation: $reason")
        }
        try {
            authority.record(CsreToolEvent(request, "authorized", ""))
        } catch (_: Exception) {
            return ToolResult(false, "CSRE audit unavailable; no tool executed.")
        }
        val result = try {
            delegate.execute(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.message ?: "tool failed"
            try {
                authority.record(CsreToolEvent(request, "failed", error))
            } catch (_: Exception) {
                // The operation may already have occurred; never pretend it did not.
                return ToolResult(false, "Tool outcome uncertain; CSRE audit unavailable. Inspect state before retry.")
            }
            return ToolResult(false, error)
        }
        try {
            authority.record(CsreToolEvent(request, if (result.ok) "completed" else "failed", result.output.take(512)))
        } catch (_: Exception) {
            return ToolResult(false, "Tool returned but CSRE outcome audit failed. Inspect state before retry.")
        }
        return result
    }
}
