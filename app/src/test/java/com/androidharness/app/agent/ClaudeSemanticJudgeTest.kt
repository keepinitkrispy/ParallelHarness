package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeSemanticJudgeTest {
    private val config = ProviderConfig(
        id = "anthropic-test",
        name = "Claude",
        type = ProviderType.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-test-model",
    )

    private class FakeProvider(private val output: String) : LlmProvider {
        var prompt: String? = null
        var toolsCount: Int? = null
        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            systemPrompt: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchema>,
            options: RequestOptions,
        ): Flow<StreamEvent> {
            prompt = systemPrompt
            toolsCount = tools.size
            return flowOf(StreamEvent.TextDelta(output), StreamEvent.Done("end_turn"))
        }
    }

    @Test fun metaEthicalPrinciplesAreInSystemMessage() {
        val prompt = AntiLlmMetaEthics.SYSTEM_PROMPT
        assertTrue(prompt.contains("HUMAN UNITY"))
        assertTrue(prompt.contains("ANTI-CAPTURE"))
        assertTrue(prompt.contains("ALTERNATIVE THINKING"))
        assertTrue(prompt.contains("RECURSIVE ACCOUNTABILITY"))
        assertTrue(prompt.contains("ADVISORY ONLY"))
        assertTrue(prompt.contains("NO enforcement"))
    }

    @Test fun configuredClaudeWritesOnlyAnAdvisoryReport() = runBlocking {
        val output = """{"verdict":"possible_semantic_drift","literal_objective":"analyze the rig","observations":["model tried to change it"],"alternative_interpretations":[],"unsupported_transitions":[],"autonomy_cost_if_wrong":"delay","recommended_investigation":"replay","enforcement_authority":"none"}"""
        val fake = FakeProvider(output)
        val file = File.createTempFile("claude-advisory-", ".jsonl")
        try {
            val judge = ClaudeSemanticJudge(
                credentials = { config to "test-key" },
                providerFactory = { fake },
                auditFile = file,
            )
            assertTrue(judge.review("session1", "analyze the rig", "I changed it.", emptyList()))
            assertEquals(AntiLlmMetaEthics.SYSTEM_PROMPT, fake.prompt)
            assertEquals(0, fake.toolsCount)
            assertTrue(file.readText().contains("ADVISORY_ONLY"))
            assertTrue(file.readText().contains("possible_semantic_drift"))
        } finally {
            file.delete()
        }
    }

    @Test fun noProviderMeansNoInventedClaudeJudgment() = runBlocking {
        val file = File.createTempFile("claude-no-provider-", ".jsonl")
        val fake = FakeProvider("{}")
        try {
            val judge = ClaudeSemanticJudge(
                credentials = { null },
                providerFactory = { fake },
                auditFile = file,
            )
            assertFalse(judge.review("session1", "analyze", "done", emptyList()))
            assertTrue(file.readText().isBlank())
        } finally {
            file.delete()
        }
    }

    @Test fun simulatedReviewerPermissionClaimDoesNotBecomeAuthority() = runBlocking {
        val fake = FakeProvider("""{"verdict":"aligned","enforcement_authority":"grant"}""")
        val file = File.createTempFile("claude-invalid-", ".jsonl")
        try {
            val judge = ClaudeSemanticJudge(
                credentials = { config to "test-key" },
                providerFactory = { fake },
                auditFile = file,
            )
            assertFalse(judge.review("session1", "do not modify", "done", emptyList()))
            assertTrue(file.readText().isBlank())
        } finally {
            file.delete()
        }
    }
}
