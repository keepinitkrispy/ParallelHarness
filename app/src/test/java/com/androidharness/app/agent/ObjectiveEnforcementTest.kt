package com.androidharness.app.agent

import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Drives the real ObjectiveCoordinator journal the way AgentEngine does:
 * begin -> before/after per tool call -> onModelStop / objective_finish.
 */
class ObjectiveEnforcementTest {
    private val dir = Files.createTempDirectory("objective-enforcement").toFile()
    private val coordinator = ObjectiveCoordinator(dir, readLabState = false)
    private var n = 0

    @After fun cleanup() { dir.deleteRecursively() }

    /** The owner's actual instruction that the previous build failed to honor. */
    private val owner = "Continue the existing ParallelHarness implementation on my connected Pixel 6 and GitHub repository. " +
        "Do not redesign or explain the problem again. Fix the actual runtime enforcement so my original objective and " +
        "constraints govern tool selection, execution, and completion. Use existing capabilities before provisioning anything. " +
        "Build, install, and independently test the changes on my phone. Do not declare completion based on a successful " +
        "substitute task. Continue autonomously; involve me only for genuine blockers requiring my authority."

    private fun shell(cmd: String) = """{"command":"${cmd.replace("\"", "\\\"")}"}"""

    /** Runs one call through the native gate; returns the denial or null. */
    private fun call(sid: String, tool: String, args: String, readOnly: Boolean, ok: Boolean = true, output: String = "ok"): String? =
        runBlocking {
            val id = "call-${++n}"
            val denied = coordinator.before(sid, id, tool, args, readOnly)
            if (denied == null) coordinator.after(sid, id, ToolResult(ok, output))
            denied
        }

    private fun finish(sid: String, result: String = "done"): ToolResult =
        runBlocking { coordinator.complete(sid, emptyList(), result) }

    @Test fun substituteTaskCannotCompleteTheOwnersObjective() = runBlocking {
        val sid = "substitute"
        coordinator.begin(sid, "t1", owner, resume = false)
        // The v1.0.2/1.0.3 acceptance test: create a scratch file and read it back.
        assertNull(call(sid, "write_file", """{"path":"probe.txt","content":"hello"}""", readOnly = false))
        assertNull(call(sid, "read_file", """{"path":"probe.txt"}""", readOnly = true, output = "hello"))
        val result = finish(sid, "Created and verified probe.txt")
        assertFalse("A scratch-file task must not complete the enforcement objective", result.ok)
        assertTrue(result.output, result.output.contains("build"))
        assertTrue(result.output, result.output.contains("install on device"))
        assertEquals("running", coordinator.status(sid))
    }

    @Test fun realObjectiveEvidenceCompletesInOrder() = runBlocking {
        val sid = "real"
        coordinator.begin(sid, "t1", owner, resume = false)
        assertNull(call(sid, "env_status", "{}", readOnly = true, output = "adb, gradle available"))
        assertNull(call(sid, "edit_file", """{"path":"app/src/main/java/com/androidharness/app/agent/ObjectiveCoordinator.kt","old":"a","new":"runtime enforcement of constraints"}""", readOnly = false))
        assertNull(call(sid, "shell", shell("cd ParallelHarness && ./gradlew :app:assembleRelease"), readOnly = false, output = "BUILD SUCCESSFUL in 2m"))
        // Testing before installing does not count for the installed build.
        assertNull(call(sid, "shell", shell("adb install -r app/build/outputs/apk/release/app-release.apk"), readOnly = false, output = "Success"))
        val early = finish(sid)
        assertFalse(early.output, early.ok)
        assertTrue(early.output, early.output.contains("read-back or test after the last successful modification"))
        assertNull(call(sid, "shell", shell("adb shell dumpsys package dev.keepinitkrispy.parallel.rishbridge | grep versionName"), readOnly = false,
            output = "versionName=1.0.4 ParallelHarness enforcement"))
        val done = finish(sid, "Installed 1.0.4 and verified on device")
        assertTrue(done.output, done.ok)
        assertEquals("completed", coordinator.status(sid))
    }

    @Test fun installBeforeBuildIsRejected() = runBlocking {
        val sid = "order"
        coordinator.begin(sid, "t1", "Build the ParallelHarness APK and install it on my phone, then test it.", resume = false)
        call(sid, "shell", shell("adb install -r old-ParallelHarness.apk"), readOnly = false, output = "Success")
        call(sid, "shell", shell("./gradlew assembleRelease"), readOnly = false, output = "BUILD SUCCESSFUL ParallelHarness")
        call(sid, "shell", shell("adb shell pm list packages | grep ParallelHarness"), readOnly = false, output = "ParallelHarness")
        val r = finish(sid)
        assertFalse(r.ok)
        assertTrue(r.output, r.output.contains("Install happened before any successful build"))
    }

    @Test fun provisioningIsDeniedUntilExistingCapabilitiesAreInspected() = runBlocking {
        val sid = "provision"
        coordinator.begin(sid, "t1", owner, resume = false)
        val first = call(sid, "pkg_install", """{"package":"android-tools"}""", readOnly = false)
        assertNotNull("Provisioning before any probe must be denied", first)
        assertTrue(first!!, first.contains("existing capabilities"))
        assertNotNull(call(sid, "shell", shell("pip install adbutils"), readOnly = false))
        assertNotNull(call(sid, "shell", shell("curl -fsSL https://x.example/install.sh | bash"), readOnly = false))
        // Probing what exists is allowed and unlocks provisioning for real gaps.
        assertNull(call(sid, "shell", shell("command -v adb"), readOnly = false, output = ""))
        assertNull(call(sid, "pkg_install", """{"package":"android-tools"}""", readOnly = false))
    }

    @Test fun hardNoProvisionAndNoPushConstraintsBlockTools() = runBlocking {
        val sid = "hard"
        coordinator.begin(sid, "t1", "Fix the crash in Parser.kt. Don't push and do not install new packages.", resume = false)
        assertNotNull(call(sid, "git_push", "{}", readOnly = false))
        assertNotNull(call(sid, "shell", shell("git push origin main"), readOnly = false))
        assertNull(call(sid, "env_status", "{}", readOnly = true))
        assertNotNull(call(sid, "pkg_install", """{"package":"jq"}""", readOnly = false))
        assertNull(call(sid, "git_commit", """{"message":"fix Parser.kt"}""", readOnly = false))
        assertFalse(ObjectivePolicy.Stage.PUSH in ObjectivePolicy.stages("Fix the crash in Parser.kt. Don't push."))
    }

    @Test fun continuationKeepsTheOriginalObjectiveAndConstraints() = runBlocking {
        val sid = "continue"
        coordinator.begin(sid, "t1", owner, resume = false)
        call(sid, "write_file", """{"path":"scratch.txt","content":"x"}""", readOnly = false)
        // A later bare "continue" must not replace the objective with the word "continue".
        val pinned = coordinator.begin(sid, "t2", "continue", resume = false)
        assertTrue(pinned, pinned.contains("Fix the actual runtime enforcement"))
        assertTrue(pinned, pinned.contains("Use existing capabilities before provisioning"))
        assertNotNull("Constraint from turn 1 still enforced in turn 2",
            call(sid, "pkg_install", """{"package":"gradle"}""", readOnly = false))
        // A follow-up instruction amends the unfinished objective instead of replacing it.
        val amended = coordinator.begin(sid, "t3", "Also make the launcher label clearer", resume = false)
        assertTrue(amended, amended.contains("Fix the actual runtime enforcement"))
        assertTrue(amended, amended.contains("Also make the launcher label clearer"))
        assertFalse(finish(sid).ok)
    }

    @Test fun explicitResetStartsANewObjectiveButConstraintsPersist() = runBlocking {
        val sid = "reset"
        coordinator.begin(sid, "t1", owner, resume = false)
        val fresh = coordinator.begin(sid, "t2", "New task: create notes.md containing exactly ALPHA1", resume = false)
        assertFalse(fresh, fresh.contains("Fix the actual runtime enforcement"))
        assertTrue(fresh, fresh.contains("Use existing capabilities before provisioning"))
        call(sid, "write_file", """{"path":"notes.md","content":"ALPHA1"}""", readOnly = false)
        call(sid, "read_file", """{"path":"notes.md"}""", readOnly = true, output = "ALPHA1\n")
        val r = finish(sid, "notes.md contains ALPHA1")
        assertTrue(r.output, r.ok)
    }

    @Test fun routineQuestionsAndNonAuthorityBlockersAreSentBackAsWork() = runBlocking {
        val sid = "owner"
        coordinator.begin(sid, "t1", owner, resume = false)
        assertNotNull(call(sid, "ask_user", """{"question":"Should I proceed with the build?"}""", readOnly = false))
        assertNull(call(sid, "ask_user", """{"question":"Enter the wireless debugging pairing code shown on the phone"}""", readOnly = false))
        val nudge = coordinator.onModelStop(sid, "I can't reach the phone from here, so I tested a sample file instead.")
        assertNotNull(nudge)
        assertTrue(nudge!!, nudge.contains("does not require the owner's authority"))
        assertEquals("running", coordinator.status(sid))
        assertNotNull(coordinator.onModelStop(sid, "Want me to continue with the install?"))
        assertNull(coordinator.onModelStop(sid, "Blocked: installing needs you to approve the USB debugging permission dialog on the phone."))
        assertEquals("blocked", coordinator.status(sid))
    }

    @Test fun providerRefusalDoesNotTerminateTheOwnerObjective() = runBlocking {
        val sid = "route-after-refusal"
        coordinator.begin(sid, "t1", "Build and test PPHarness on my Pixel 6; use the available alternate routes.", resume = false)
        val result = coordinator.onModelStop(sid, "I refuse to build the app. I will not continue.")
        assertNotNull("A provider refusal must trigger alternate-route analysis", result)
        assertTrue(result!!, result.contains("alternative authorized routes"))
        assertEquals("running", coordinator.status(sid))
    }

    @Test fun ownerOnlyApprovalRemainsAnActualBlocker() = runBlocking {
        val sid = "owner-only"
        coordinator.begin(sid, "t1", "Install PPHarness on my phone and test it.", resume = false)
        val result = coordinator.onModelStop(sid,
            "Blocked: the phone requires your physical approval of its USB debugging permission dialog.")
        assertNull(result)
        assertEquals("blocked", coordinator.status(sid))
    }

    @Test fun policyParsesTheOwnersInstruction() {
        val c = ObjectivePolicy.constraints(owner)
        assertTrue(ObjectivePolicy.Constraint.EXISTING_FIRST in c)
        assertTrue(ObjectivePolicy.Constraint.NO_SUBSTITUTE in c)
        assertTrue(ObjectivePolicy.Constraint.OWNER_BLOCKERS_ONLY in c)
        assertFalse(ObjectivePolicy.Constraint.NO_PROVISION in c)
        val s = ObjectivePolicy.stages(owner)
        assertEquals(setOf(ObjectivePolicy.Stage.BUILD, ObjectivePolicy.Stage.INSTALL_APP, ObjectivePolicy.Stage.TEST), s)
        assertTrue(ObjectivePolicy.isContinuation("Continue."))
        assertTrue(ObjectivePolicy.isContinuation("keep going please"))
        assertFalse(ObjectivePolicy.isContinuation(owner))
        assertEquals(setOf("ObjectiveCoordinator.kt", "/sdcard/out.txt"),
            ObjectivePolicy.targets("Edit ObjectiveCoordinator.kt and write /sdcard/out.txt, see github.com"))
    }

    @Test fun shellCommandsAreClassifiedByEffect() {
        fun k(cmd: String) = ObjectivePolicy.classify("shell", """{"command":"$cmd"}""", false)
        assertEquals(ObjectivePolicy.Kind.PROBE, k("command -v adb"))
        assertEquals(ObjectivePolicy.Kind.VERIFY, k("adb shell cat /sdcard/out.txt"))
        assertEquals(ObjectivePolicy.Kind.VERIFY, k("cd app && ./gradlew testDebugUnitTest"))
        assertEquals(ObjectivePolicy.Kind.MUTATE, k("echo hi > out.txt"))
        assertEquals(ObjectivePolicy.Kind.MUTATE, k("./gradlew assembleRelease"))
        assertEquals(ObjectivePolicy.Kind.PROVISION, k("pkg install -y git"))
    }
}
