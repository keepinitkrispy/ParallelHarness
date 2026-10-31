package com.androidharness.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executable form of the owner's objective and constraints. ObjectiveCoordinator
 * persists the journal; this object makes the decisions at the three points
 * that matter: which tool calls are admitted, whether the model may stop, and
 * whether completion evidence actually concerns the requested target.
 *
 * Pure Kotlin so every rule is unit-tested on the JVM.
 */
object ObjectivePolicy {

    enum class Constraint(val label: String) {
        EXISTING_FIRST("Use existing capabilities before provisioning anything (probe first: env_status, pkg_list, `command -v`)"),
        NO_PROVISION("Do not provision or install new packages or tools"),
        NO_COMMIT("Do not commit"),
        NO_PUSH("Do not push"),
        NO_DELETE("Do not delete files"),
        NO_SUBSTITUTE("Completion must be evidenced on the requested target, never on a substitute task"),
        OWNER_BLOCKERS_ONLY("Involve the owner only for blockers that need their authority"),
    }

    enum class Stage(val label: String) {
        BUILD("build"), INSTALL_APP("install on device"), INSTALL("install"),
        TEST("independent test"), COMMIT("commit"), PUSH("push"),
    }

    enum class Kind { PROVISION, PROBE, VERIFY, MUTATE, BOOKKEEPING }

    /** One journaled tool call as the policy sees it. */
    data class Event(
        val id: String,
        val name: String,
        val args: String,
        val readOnly: Boolean,
        val ok: Boolean,
        val output: String = "",
    ) {
        val kind: Kind get() = classify(name, args, readOnly)
        fun mentions(anchor: String): Boolean =
            args.contains(anchor, ignoreCase = true) || output.contains(anchor, ignoreCase = true)
    }

    // ---------------------------------------------------------------- parsing

    private val continuation = Regex(
        "^(please |ok(ay)?,? |now )?(continue|keep going|keep at it|keep working|resume|proceed|carry on|go on|" +
            "go ahead|try again|retry|finish( it| up)?|do it|yes|ok(ay)?|next|go)" +
            "( (please|now|autonomously|from where you (left|stopped) off|where you left off|with (it|that|the task)))*$"
    )

    /** A bare "continue"-style message inherits the governing objective instead of replacing it. */
    fun isContinuation(text: String): Boolean =
        continuation.matches(text.trim().lowercase().trimEnd('.', '!', ' ', '\n'))

    /** An explicit reset is the only way a follow-up drops an unfinished objective. */
    fun isExplicitReset(text: String): Boolean =
        Regex("^(new (task|objective)|start over|forget (that|the previous)|cancel (that|the previous))\\b")
            .containsMatchIn(text.trim().lowercase())

    private val neg = "(?:do not|don't|dont|never|no need to|must not|mustn't|shouldn't|should not|stop)"

    fun constraints(text: String): Set<Constraint> {
        val t = text.lowercase()
        val out = linkedSetOf<Constraint>()
        if (Regex("(use|reuse|prefer|exhaust|try) (the |my |your |what'?s? )?(existing|available|installed|current)[^.\\n]{0,60}(before|prior to|first)").containsMatchIn(t) ||
            Regex("before (provisioning|installing|adding) (anything|new|more)").containsMatchIn(t)) {
            out += Constraint.EXISTING_FIRST
        }
        if (Regex("$neg (provision|install (any|new|extra|additional)|add (new |any )?(packages|dependencies|tools))").containsMatchIn(t) ||
            Regex("without (provisioning|installing)").containsMatchIn(t)) {
            out += Constraint.NO_PROVISION
        }
        if (Regex("$neg (git )?commit").containsMatchIn(t)) out += Constraint.NO_COMMIT
        if (Regex("$neg (git )?push").containsMatchIn(t)) out += Constraint.NO_PUSH
        if (Regex("$neg (delete|remove|rm) ").containsMatchIn(t)) out += Constraint.NO_DELETE
        if (Regex("(substitute|stand-?in|proxy|dummy|placeholder|toy|mock)[^.\\n]{0,20}(task|test|result|objective)").containsMatchIn(t) &&
            Regex("$neg|not |never").containsMatchIn(t)) {
            out += Constraint.NO_SUBSTITUTE
        }
        if (Regex("(involve|ask|bother|interrupt|contact) me only|only (involve|ask|contact) me|genuine blockers?|" +
                "$neg (ask|check with) me|without asking( me)?|don't ask for (routine )?(approval|permission)").containsMatchIn(t)) {
            out += Constraint.OWNER_BLOCKERS_ONLY
        }
        return out
    }

    /** Negated clauses ("don't push", "without installing") never become required stages. */
    private fun affirmative(text: String): String =
        text.lowercase().split(Regex("[.;!?\\n]")).joinToString(". ") { clause ->
            val cut = Regex("\\b(do not|don't|dont|never|without|no need to|must not|shouldn't|should not|instead of|rather than|stop)\\b")
                .find(clause)?.range?.first
            if (cut == null) clause else clause.substring(0, cut)
        }

    fun stages(text: String): Set<Stage> {
        val t = affirmative(text)
        val out = linkedSetOf<Stage>()
        val device = Regex("\\b(apk|app|phone|device|pixel|android|emulator)\\b").containsMatchIn(t)
        if (Regex("\\b(build|compile|assemble)\\b").containsMatchIn(t) &&
            Regex("\\b(apk|app|binary|release|artifact|gradle|package|install)").containsMatchIn(t)) out += Stage.BUILD
        if (Regex("\\b(install|reinstall|sideload)\\b").containsMatchIn(t)) {
            out += if (device) Stage.INSTALL_APP else Stage.INSTALL
        }
        if (Regex("\\b(test|tests|testing|tested)\\b").containsMatchIn(t)) out += Stage.TEST
        if (Regex("\\bcommit\\b").containsMatchIn(t)) out += Stage.COMMIT
        if (Regex("\\bpush\\b(?! notification)").containsMatchIn(t)) out += Stage.PUSH
        return out
    }

    private val fileExt = "kt|kts|java|py|js|mjs|ts|tsx|jsx|json|md|txt|xml|sh|apk|gradle|yml|yaml|toml|c|cc|cpp|h|hpp|rs|go|html|css|csv|sql|ini|cfg|conf|properties"

    /** Concrete artifacts the owner named: quoted strings, file names, absolute paths. */
    fun targets(text: String): Set<String> {
        val out = linkedSetOf<String>()
        Regex("`([^`\\n]{2,120})`").findAll(text).forEach { out += it.groupValues[1].trim() }
        Regex("\"([^\"\\n]{2,120})\"").findAll(text).forEach { out += it.groupValues[1].trim() }
        Regex("(?<![\\w/.-])((?:[\\w.-]+/)*[\\w-]+\\.(?:$fileExt))\\b").findAll(text).forEach { out += it.groupValues[1] }
        Regex("(?<![\\w])((?:/|~/)[\\w.-]+(?:/[\\w.-]+)+)").findAll(text).forEach { out += it.groupValues[1].trimEnd('.') }
        return out.filter { it.isNotBlank() }.toSet()
    }

    fun exactContent(text: String): String? =
        Regex("(?i)containing exactly\\s+([A-Za-z0-9_-]+)").find(text)?.groupValues?.getOrNull(1)

    private val stopwords = setOf(
        "about", "above", "actual", "actually", "after", "again", "against", "already", "always", "another",
        "anything", "autonomously", "based", "because", "before", "being", "below", "between", "blocker",
        "blockers", "build", "capabilities", "changes", "check", "complete", "completion", "connected",
        "constraints", "continue", "could", "current", "declare", "design", "does", "doing", "during", "every",
        "everything", "existing", "explain", "finish", "first", "genuine", "govern", "having", "independently",
        "install", "instead", "involve", "later", "léger", "making", "maybe", "might", "never", "objective",
        "original", "other", "please", "point", "problem", "provisioning", "really", "redesign", "requiring",
        "result", "should", "something", "starting", "still", "substitute", "successful", "their", "there",
        "these", "thing", "things", "those", "through", "under", "until", "using", "verified", "verify", "where",
        "which", "while", "without", "would", "authority", "findings", "phone", "project", "rather", "start",
        "make", "sure", "properly", "correctly", "completely", "anymore", "afterwards", "testing", "tests",
        "install", "installed", "build", "built", "commit", "push", "pushed", "update", "updated", "change",
    )

    /** Distinctive words of the goal, used when no explicit target was named. */
    fun salientTerms(text: String): Set<String> =
        Regex("[A-Za-z][A-Za-z0-9_]{4,}").findAll(affirmative(text))
            .map { it.value.lowercase() }
            .filter { it !in stopwords }
            .toCollection(linkedSetOf())

    // --------------------------------------------------------- classification

    fun command(args: String): String? = runCatching {
        Json.parseToJsonElement(args).jsonObject["command"]?.jsonPrimitive?.content
    }.getOrNull()

    private val bookkeeping = setOf("objective_finish", "todo_write", "memory_write", "memory_read",
        "memory_search", "skill_view", "skills_list", "ask_user", "task")
    private val probeTools = setOf("env_status", "doctor", "pkg_list", "pkg_search", "list_dir", "file_info",
        "search_files", "grep", "codegraph_explore", "codegraph_node", "skills_list")
    private val verifyTools = setOf("read_file", "read_image", "list_dir", "file_info", "grep", "search_files",
        "git_status", "git_diff", "git_log", "git_show", "read_logcat", "browser_get_dom", "browser_get_url",
        "browser_get_logs", "browser_screenshot", "notification_list", "env_status", "pkg_list")

    private val provisionCmd = Regex(
        "\\b(pkg|apt|apt-get|pip3?|pipx|npm|pnpm|yarn|gem|cargo|brew|conda|uv)\\s+(install|add|i)\\b|" +
            "python3? -m pip install|\\bgo (install|get)\\b|sdkmanager\\b|\\bdpkg -i\\b|" +
            "(curl|wget)[^|]*\\|\\s*(ba)?sh\\b"
    )
    private val probeCmd = Regex(
        "^(command -v|which|type|whereis|ls|pkg list|pkg list-installed|dpkg -l|pip3? (list|show)|npm (ls|list)|" +
            "env|printenv|uname|getprop|pm list|cmd package list|dumpsys package)\\b|\\s--version\\b|\\s-v$"
    )
    private val readOnlyHead = Regex(
        "^(cat|ls|stat|head|tail|grep|rg|wc|sha256sum|sha1sum|md5sum|diff|cmp|test|\\[|file|find|tree|du|" +
            "git (status|diff|log|show|rev-parse|ls-files|ls-remote|remote)|pm (list|path|dump)|cmd package (list|resolve-activity)|" +
            "dumpsys|getprop|logcat|apksigner verify|aapt2? dump|unzip -l|jq|echo|printf|true|curl|wget -q?O-|" +
            "am (start|start-activity|broadcast|instrument)|monkey|uiautomator|input|screencap|" +
            "pytest|python3? -m (pytest|unittest)|npm (test|run test)|go test|cargo test|ctest|" +
            "(\\./)?gradlew?\\b[^&;|]*\\b(test\\w*|check|connected\\w*Test)\\b)"
    )
    private val writesFs = Regex("(^|[^0-9&])>(?!\\s*/dev/null)|\\brm\\s|\\bmv\\s|\\bcp\\s|sed -i|\\btee\\b|-delete\\b|--output|-o\\s")

    private fun segments(cmd: String): List<String> {
        var c = cmd.trim()
        // Device-side wrappers do not change what the inner command does.
        c = c.replace(Regex("\\badb(\\s+-s\\s+\\S+)?\\s+shell\\s+"), "")
            .replace(Regex("\\b(rish|su)\\s+-c\\s+"), "")
            .replace("\"", "").replace("'", "")
        return c.split(Regex("&&|\\|\\||;|\\|")).map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.startsWith("cd ") || it == "cd" || it.startsWith("export ") || it.startsWith("set -") }
    }

    fun classify(name: String, args: String, readOnly: Boolean): Kind {
        if (name in bookkeeping) return Kind.BOOKKEEPING
        if (name == "pkg_install") return Kind.PROVISION
        if (name == "shell" || name == "shell_background") {
            val cmd = command(args) ?: return Kind.MUTATE
            if (provisionCmd.containsMatchIn(cmd)) return Kind.PROVISION
            val parts = segments(cmd)
            if (parts.isEmpty()) return Kind.MUTATE
            if (!writesFs.containsMatchIn(cmd) && parts.all { probeCmd.containsMatchIn(it) }) return Kind.PROBE
            if (!writesFs.containsMatchIn(cmd) && parts.all { readOnlyHead.containsMatchIn(it) || probeCmd.containsMatchIn(it) }) return Kind.VERIFY
            return Kind.MUTATE
        }
        if (name in probeTools && readOnly && name !in verifyTools) return Kind.PROBE
        if (readOnly) return if (name in verifyTools || name.startsWith("browser_get") ||
            name.startsWith("read_") || name.contains("delegation_result")) Kind.VERIFY else Kind.PROBE
        return Kind.MUTATE
    }

    private fun isProbe(e: Event): Boolean = e.kind == Kind.PROBE ||
        (e.name in probeTools && e.readOnly)

    private fun commitCall(name: String, args: String) =
        name == "git_commit" || (command(args)?.let { Regex("\\bgit\\s+commit\\b").containsMatchIn(it) } ?: false)
    private fun pushCall(name: String, args: String) =
        name == "git_push" || (command(args)?.let { Regex("\\bgit\\s+push\\b").containsMatchIn(it) } ?: false)
    private fun deleteCall(name: String, args: String) =
        name == "delete_file" || (command(args)?.let { Regex("(^|[;&|]\\s*)rm\\s").containsMatchIn(it.trim()) } ?: false)

    fun stageEvidence(stage: Stage, e: Event): Boolean {
        if (!e.ok) return false
        val cmd = command(e.args).orEmpty()
        return when (stage) {
            Stage.BUILD -> Regex("\\b(gradlew?|assemble\\w*|make|cmake|cargo build|go build|npm run build|yarn build|mvn|tsc|kotlinc|javac|bundle\\w*)\\b").containsMatchIn(cmd) ||
                e.output.contains("BUILD SUCCESSFUL") || (e.name.startsWith("mcp__") && e.name.contains("build"))
            Stage.INSTALL_APP -> Regex("\\b(pm install|adb( -s \\S+)? install|cmd package install|install-(create|write|commit)|install-multiple)\\b").containsMatchIn(cmd) ||
                (e.name.startsWith("mcp__") && e.name.contains("install"))
            Stage.INSTALL -> e.name == "pkg_install" || provisionCmd.containsMatchIn(cmd) ||
                Regex("\\b(pm install|adb install)\\b").containsMatchIn(cmd)
            Stage.TEST -> (e.kind == Kind.VERIFY && cmd.isNotEmpty()) || e.name == "read_logcat" ||
                (e.name.startsWith("browser_") && e.name != "browser_navigate") ||
                (e.name.startsWith("mcp__") && Regex("test|verify|check").containsMatchIn(e.name))
            Stage.COMMIT -> commitCall(e.name, e.args)
            Stage.PUSH -> pushCall(e.name, e.args)
        }
    }

    // --------------------------------------------------------------- admission

    /**
     * Decides whether a tool call may run under the owner's constraints.
     * Returns the denial shown to the model, or null when admitted.
     */
    fun admit(name: String, args: String, readOnly: Boolean, constraints: Set<Constraint>, journal: List<Event>): String? {
        val kind = classify(name, args, readOnly)
        if (kind == Kind.PROVISION) {
            if (Constraint.NO_PROVISION in constraints)
                return "Denied by owner constraint: \"${Constraint.NO_PROVISION.label}\". Accomplish this with what is already installed."
            if (Constraint.EXISTING_FIRST in constraints && journal.none { it.ok && isProbe(it) })
                return "Denied by owner constraint: \"${Constraint.EXISTING_FIRST.label}\". No existing capability has been inspected yet in this objective. " +
                    "Run env_status, pkg_list, or `command -v <tool>` first and use what exists; provision only if that shows it is missing."
        }
        if (Constraint.NO_COMMIT in constraints && commitCall(name, args))
            return "Denied by owner constraint: \"${Constraint.NO_COMMIT.label}\"."
        if (Constraint.NO_PUSH in constraints && pushCall(name, args))
            return "Denied by owner constraint: \"${Constraint.NO_PUSH.label}\"."
        if (Constraint.NO_DELETE in constraints && deleteCall(name, args))
            return "Denied by owner constraint: \"${Constraint.NO_DELETE.label}\"."
        if (name == "ask_user" && Constraint.OWNER_BLOCKERS_ONLY in constraints) {
            val q = runCatching { Json.parseToJsonElement(args).jsonObject["question"]?.jsonPrimitive?.content }.getOrNull() ?: args
            if (!ownerAuthority(q))
                return "Denied by owner constraint: \"${Constraint.OWNER_BLOCKERS_ONLY.label}\". This question does not require the owner's credentials, " +
                    "payment, consent, physical action or a permission grant. Decide using the objective and evidence, then continue."
        }
        return null
    }

    private val authority = Regex(
        "password|passcode|passphrase|credential|api[ -]?key|secret|token|2fa|two[- ]factor|otp|verification code|" +
            "sign[- ]?in|log ?in|captcha|payment|purchase|billing|credit card|\\bpay\\b|consent|legal|" +
            "physical(ly)?|plug (in|the)|unlock (the|your)|biometric|fingerprint|usb debugging|wireless debugging|" +
            "pair(ing)? code|grant[^.]{0,40}permission|permission[^.]{0,20}(grant|dialog|prompt)|authori[sz]|" +
            "only you can|your (authority|approval to spend|account)|tap (allow|install|accept)",
        RegexOption.IGNORE_CASE,
    )

    fun ownerAuthority(text: String): Boolean = authority.containsMatchIn(text)

    private val blockerClaim = Regex(
        "\\b(cannot|can't|can not|unable to|blocked|not permitted|not authori[sz]ed|no (access|way)|impossible|" +
            "need (you|your)|requires? (you|your)|you('ll| will) need to|please (provide|grant|run|install|enable))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val refusal = Regex("\\bI (won't|will not|refuse to|am not going to)\\b", RegexOption.IGNORE_CASE)

    sealed interface StopVerdict {
        /** The model may end the run; status records why. */
        data class Allow(val status: String) : StopVerdict
        data class Nudge(val message: String) : StopVerdict
    }

    /**
     * The model wants to stop without a verified completion. Only a genuine
     * refusal or an owner-authority blocker ends the run; anything else is
     * sent back as work.
     */
    fun onStop(reply: String, constraints: Set<Constraint>): StopVerdict? {
        if (refusal.containsMatchIn(reply)) {
            // A provider's refusal does not by itself establish that the
            // owner's objective is impossible. Request a concrete alternate
            // authorized route; genuine user-only steps still require the owner.
            return StopVerdict.Nudge("The provider declined this approach. Retain the user's original " +
                "objective; consult Qwen for alternative authorized routes, change the plan or tools, and " +
                "execute a materially different route. Do not retry prohibited operations. " +
                "Only a verified hard constraint or an actual owner-only dependency can stop execution.")
        }
        if (blockerClaim.containsMatchIn(reply)) {
            return if (ownerAuthority(reply)) StopVerdict.Allow("blocked")
            else StopVerdict.Nudge("The stated blocker does not require the owner's authority (credentials, payment, consent, " +
                "physical action or a permission grant). Treat it as a variable: inspect what exists, pick an alternative route " +
                "with the available tools, and continue toward the original objective.")
        }
        val tail = reply.takeLast(240)
        if (Constraint.OWNER_BLOCKERS_ONLY in constraints && tail.contains('?') && !ownerAuthority(tail))
            return StopVerdict.Nudge("Do not ask the owner for routine direction or approval. Decide from the objective and evidence and continue executing.")
        return null
    }

    // -------------------------------------------------------------- completion

    data class Spec(
        val goal: String,
        val constraints: Set<Constraint>,
        val actionTask: Boolean,
    ) {
        val targets: Set<String> = targets(goal)
        val stages: Set<Stage> = stages(goal)
        val terms: Set<String> = salientTerms(goal)
        val exact: String? = exactContent(goal)
    }

    /**
     * Returns every unmet requirement for completing [spec] from the journal,
     * empty when completion is evidenced. Requirements are bound to the
     * owner's objective, so a successful unrelated task never satisfies them.
     */
    fun unmet(spec: Spec, journal: List<Event>, result: String): List<String> {
        val ok = journal.filter { it.ok && it.kind != Kind.BOOKKEEPING }
        val missing = mutableListOf<String>()
        if (result.isBlank()) missing += "objective_finish needs a result describing the observed outcome"
        if (ok.isEmpty()) return missing + "No successful tool execution exists for this objective"
        if (!spec.actionTask) return missing

        val mutations = ok.withIndex().filter { it.value.kind == Kind.MUTATE || it.value.kind == Kind.PROVISION }
        if (mutations.isEmpty()) return missing + "No successful action changed anything toward the objective"
        val lastMutation = mutations.last().index
        val postChecks = ok.withIndex().filter { it.index > lastMutation && it.value.kind == Kind.VERIFY }.map { it.value }
        if (postChecks.isEmpty())
            missing += "No independent read-back or test after the last successful modification"

        val anchors = spec.targets.ifEmpty { spec.terms }
        if (spec.targets.isNotEmpty()) {
            val untouched = spec.targets.filter { t -> ok.none { it.mentions(t) } }
            if (untouched.isNotEmpty())
                missing += "Requested target(s) never touched by any successful tool call: " + untouched.joinToString(", ")
        } else if (spec.terms.isNotEmpty()) {
            val hit = spec.terms.count { term -> ok.any { it.mentions(term) } }
            val need = minOf(2, spec.terms.size)
            if (hit < need)
                missing += "Tool evidence does not concern the requested objective (none of its key terms appear: " +
                    spec.terms.take(8).joinToString(", ") + "). A different, easier task does not complete it"
        }
        if (anchors.isNotEmpty()) {
            if (mutations.none { m -> anchors.any { m.value.mentions(it) } })
                missing += "No successful modification acted on the requested objective (" + anchors.take(6).joinToString(", ") + ")"
            if (postChecks.isNotEmpty() && spec.stages.isEmpty() && postChecks.none { c -> anchors.any { c.mentions(it) } })
                missing += "The read-back after the last modification inspected something other than the requested objective"
        }

        val stageIdx = spec.stages.associateWith { s -> ok.withIndex().filter { stageEvidence(s, it.value) }.map { it.index } }
        val absent = stageIdx.filterValues { it.isEmpty() }.keys
        if (absent.isNotEmpty())
            missing += "Required stage(s) with no successful evidence: " + absent.joinToString(", ") { it.label }
        val build = stageIdx[Stage.BUILD].orEmpty()
        val install = (stageIdx[Stage.INSTALL_APP].orEmpty() + stageIdx[Stage.INSTALL].orEmpty())
        val test = stageIdx[Stage.TEST].orEmpty()
        if (build.isNotEmpty() && install.isNotEmpty() && install.max() < build.min())
            missing += "Install happened before any successful build of the changes"
        if (install.isNotEmpty() && test.isNotEmpty() && test.max() < install.max())
            missing += "No test ran against the latest installed version"
        if (spec.stages.contains(Stage.TEST) && test.isNotEmpty() && mutations.isNotEmpty() &&
            install.isEmpty() && test.max() < lastMutation)
            missing += "No test ran after the last modification"

        val exact = spec.exact
        if (exact != null && postChecks.none { it.output.contains(exact) })
            missing += "Read-back does not contain the exact requested content"
        return missing
    }
}
