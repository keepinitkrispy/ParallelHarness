package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NativeObjectiveRecoveryTest {
    @Test fun restoreOnlyProcessInterruptedRuns() {
        val dir = Files.createTempDirectory("native-recovery").toFile()
        try {
            val first = TaskControlStore(dir)
            first.update("crashed-1") { it.copy(status = "running") }
            first.update("owner-paused") { it.copy(status = "paused") }
            first.update("user-stopped") { it.copy(status = "idle") }
            first.update("already-completed") { it.copy(status = "idle") }
            // A separate store is a new Android process loading the durable records.
            val recovered = TaskControlStore(dir)
            assertEquals(listOf("crashed-1"), recovered.interruptedSessionIds())
            assertEquals("interrupted", recovered.flow("crashed-1").value.status)
            assertEquals("paused", recovered.flow("owner-paused").value.status)
            assertTrue("No owner-paused job may be auto-resumed",
                "owner-paused" !in recovered.interruptedSessionIds())
        } finally {
            dir.deleteRecursively()
        }
    }
}
