package com.androidharness.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

internal class RememberedGrants {
    data class Grant(val sessionId: String, val workspacePath: String, val key: String)
    private data class Run(val workspacePath: String, val keys: MutableSet<String>)
    private val runs = mutableMapOf<String, Run>()
    private val state = MutableStateFlow<List<Grant>>(emptyList())
    val grants: StateFlow<List<Grant>> = state

    @Synchronized fun start(sessionId: String, workspacePath: String): MutableSet<String> {
        val keys = ConcurrentHashMap.newKeySet<String>()
        runs[sessionId] = Run(workspacePath, keys)
        publish()
        return keys
    }

    @Synchronized fun remember(sessionId: String, key: String) {
        runs[sessionId]?.keys?.add(key)
        publish()
    }

    @Synchronized fun revoke(grant: Grant) {
        runs[grant.sessionId]?.takeIf { it.workspacePath == grant.workspacePath }?.keys?.remove(grant.key)
        publish()
    }

    @Synchronized fun finish(sessionId: String) {
        runs.remove(sessionId)?.keys?.clear()
        publish()
    }

    private fun publish() {
        state.value = runs.flatMap { (session, run) -> run.keys.map { Grant(session, run.workspacePath, it) } }
            .sortedWith(compareBy({ it.workspacePath }, { it.key }, { it.sessionId }))
    }
}
