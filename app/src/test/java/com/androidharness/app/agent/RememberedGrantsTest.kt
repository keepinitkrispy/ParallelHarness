package com.androidharness.app.agent

import com.androidharness.app.tools.ShellPolicy
import org.junit.Assert.*
import org.junit.Test

class RememberedGrantsTest {
    @Test fun revocationUpdatesEngineSet() {
        val store = RememberedGrants()
        val keys = store.start("chat", "/workspace")
        store.remember("chat", "shell#git status")
        assertTrue(ShellPolicy.isGranted("shell", "git status", keys))
        store.revoke(store.grants.value.single())
        assertFalse(ShellPolicy.isGranted("shell", "git status", keys))
        assertTrue(store.grants.value.isEmpty())
    }

    @Test fun revocationIsScopedToRunAndWorkspace() {
        val store = RememberedGrants()
        val a = store.start("a", "/a")
        val b = store.start("b", "/b")
        store.remember("a", "write_file")
        store.remember("b", "write_file")
        store.revoke(RememberedGrants.Grant("a", "/b", "write_file"))
        assertTrue(a.contains("write_file"))
        store.revoke(RememberedGrants.Grant("a", "/a", "write_file"))
        assertFalse(a.contains("write_file"))
        assertTrue(b.contains("write_file"))
    }

    @Test fun finishClearsGrantsAndLateApprovalDoesNotResurrectThem() {
        val store = RememberedGrants()
        val keys = store.start("a", "/a")
        store.remember("a", "write_file")
        store.finish("a")
        store.remember("a", "write_file")
        assertTrue(keys.isEmpty())
        assertTrue(store.grants.value.isEmpty())
    }

    @Test fun nextRunStartsWithoutPermissions() {
        val store = RememberedGrants()
        store.start("a", "/a")
        store.remember("a", "write_file")
        store.finish("a")
        assertTrue(store.start("a", "/a").isEmpty())
    }
}
