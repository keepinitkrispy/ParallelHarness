package com.androidharness.app.tools

import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests harness wiring, not the signed-chain implementation. */
class CsreAuthorityTest {
    private class FakeWorkspace : WorkspaceFs {
        override val displayPath = "workspace-test"
        override val shellRoot: java.io.File? = null
        override val isSaf = true
        override fun resolve(path: String): FsNode = error("unexpected workspace access")
        override fun walk(path: String): Sequence<FsNode> = emptySequence()
    }

    private class StubTool(
        override val name: String = "write_file",
        override val isReadOnly: Boolean = false,
    ) : Tool {
        var calls = 0
        override val description = "fake"
        override val parametersSchema: JsonObject = buildJsonObject { }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            calls++
            return ToolResult(true, "done")
        }
    }

    private class FakeAuthority(var decision: CsreToolDecision) : CsreAuthority {
        val checked = mutableListOf<CsreToolRequest>()
        val events = mutableListOf<CsreToolEvent>()
        var throwOnDecision = false
        override suspend fun decide(request: CsreToolRequest): CsreToolDecision {
            if (throwOnDecision) error("authority offline")
            checked += request
            return decision
        }
        override suspend fun record(event: CsreToolEvent) { events += event }
    }

    private val ctx = ToolContext(FakeWorkspace(), sessionId = "session1")
    private val args = buildJsonObject {}

    @Test fun denialStopsExecution() = runBlocking {
        val tool = StubTool()
        val auth = FakeAuthority(CsreToolDecision.Deny("no signed grant"))
        val result = CsreGatedTool(tool, auth).execute(args, ctx)
        assertFalse(result.ok)
        assertEquals(0, tool.calls)
        assertEquals(listOf("blocked"), auth.events.map { it.outcome })
    }

    @Test fun allowExecutesAndRecordsOutcome() = runBlocking {
        val tool = StubTool()
        val auth = FakeAuthority(CsreToolDecision.Allow)
        val result = CsreGatedTool(tool, auth).execute(args, ctx)
        assertTrue(result.ok)
        assertEquals(1, tool.calls)
        assertEquals("write_file", auth.checked.single().operation)
        assertEquals("workspace-test", auth.checked.single().resource)
        assertEquals(listOf("authorized", "completed"), auth.events.map { it.outcome })
    }

    @Test fun missingAuthorityFailsClosed() = runBlocking {
        val tool = StubTool()
        val auth = FakeAuthority(CsreToolDecision.Allow)
        auth.throwOnDecision = true
        assertFalse(CsreGatedTool(tool, auth).execute(args, ctx).ok)
        assertEquals(0, tool.calls)
    }

    @Test fun readOnlyMetadataCannotBypassGate() = runBlocking {
        val tool = StubTool("untrusted_mcp_read", isReadOnly = true)
        val auth = FakeAuthority(CsreToolDecision.Deny("not authorized"))
        assertFalse(CsreGatedTool(tool, auth).execute(args, ctx).ok)
        assertEquals(0, tool.calls)
    }

    @Test fun extraToolsInheritGate() = runBlocking {
        val tool = StubTool("mcp_action")
        val auth = FakeAuthority(CsreToolDecision.Deny("not authorized"))
        val registry = ToolRegistry(emptyList()).withCsreAuthority(auth).withExtra(listOf(tool))
        val result = registry.get("mcp_action")!!.execute(args, ctx)
        assertFalse(result.ok)
        assertEquals(0, tool.calls)
    }

    @Test fun baseAndExtrasUseSameGate() = runBlocking {
        val base = StubTool("base")
        val extra = StubTool("extra")
        val auth = FakeAuthority(CsreToolDecision.Deny("not authorized"))
        val registry = ToolRegistry(listOf(base)).withCsreAuthority(auth).withExtra(listOf(extra))
        registry.get("base")!!.execute(args, ctx)
        registry.get("extra")!!.execute(args, ctx)
        assertEquals(0, base.calls)
        assertEquals(0, extra.calls)
        assertEquals(2, auth.checked.size)
    }
}
