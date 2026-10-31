package com.androidharness.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.androidharness.app.HarnessApp
import com.androidharness.app.RunResultNotification
import com.androidharness.app.RunResultNotifications
import com.androidharness.app.llm.HarnessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * Durable on-device continuation of interrupted native objectives.
 *
 * ChatGPT's external tool gateway is not the execution loop. The native
 * RunManager's model chooses work and executes tools. ClaudeUI only supplies
 * model replies through an optional MCP tool; it never owns the objective.
 *
 * Only process-interrupted ACT tasks are resumed. A user pause/stop, a failed
 * task with an explicit reason, and a verified completion are never resumed
 * by this worker. Existing native tool receipts protect against replaying
 * actions with uncertain outcomes after a process death.
 */
class NativeObjectiveRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private suspend fun alertBlocked(sessionId: String, reason: String) {
        val c = (applicationContext as? HarnessApp)?.container ?: return
        val title = c.sessions.session(sessionId)?.title ?: "Autonomous task"
        RunResultNotifications.post(applicationContext, RunResultNotification(
            sessionId = sessionId,
            title = title,
            ok = false,
            summary = reason,
            notificationTitle = "ParallelHarness task needs attention",
        ))
    }

    override suspend fun doWork(): Result {
        val c = (applicationContext as? HarnessApp)?.container ?: return Result.retry()
        // Keep Termux-backed execution and Claude delegation available without
        // a remote ChatGPT controller. Failure is nonfatal for local-only jobs.
        LocalServicesSupervisor.ensureStarted(applicationContext)
        val controls = c.runManager.controls
        val interrupted = controls.interruptedSessionIds()
        if (interrupted.isEmpty()) return Result.success()
        setForeground(getForegroundInfo())

        for (sessionId in interrupted) {
            currentCoroutineContext().ensureActive()
            if (c.runManager.isRunning(sessionId)) continue
            val record = controls.flow(sessionId).value
            if (record.status != "interrupted" || record.mode != AgentMode.ACT.name) continue
            val provider = record.provider
            if (provider == null) {
                val reason = "Saved model provider is unavailable"
                controls.update(sessionId) { it.copy(status = "paused", reason = reason) }
                alertBlocked(sessionId, reason)
                continue
            }
            val key = if (provider.id == HarnessProvider.ID) c.providers.harnessApiKey()
                else c.providers.apiKey(provider.id)
            if (key.isNullOrBlank()) {
                val reason = "Saved model credentials are unavailable"
                controls.update(sessionId) { it.copy(status = "paused", reason = reason) }
                alertBlocked(sessionId, reason)
                continue
            }
            try {
                // Continue the original session/turn, never create a replacement
                // objective or repeat successful non-read-only tool calls.
                c.runManager.resumeTask(sessionId, key)
                while (c.runManager.isRunning(sessionId)) {
                    currentCoroutineContext().ensureActive()
                    delay(1500)
                }
            } catch (cancelled: CancellationException) {
                // WorkManager will reschedule after an OS interruption.
                // Do not relabel the owner's task as completed or consented.
                throw cancelled
            } catch (e: Exception) {
                if (runAttemptCount >= 2) {
                    val reason = "Automatic resume failed: " + (e.message ?: "unknown").take(200)
                    controls.update(sessionId) { it.copy(status = "paused", reason = reason) }
                    alertBlocked(sessionId, reason)
                } else {
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Autonomous objective recovery",
                NotificationManager.IMPORTANCE_LOW))
        val notice = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("ParallelHarness")
            .setContentText("Continuing an interrupted objective")
            .setOngoing(true)
            .build()
        return ForegroundInfo(id.hashCode(), notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "native-objective-recovery"
        private const val STARTUP = "native-objective-recovery-startup"
        private const val PERIODIC = "native-objective-recovery-periodic"

        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            work.enqueueUniqueWork(STARTUP, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NativeObjectiveRecoveryWorker>()
                    .setConstraints(constraints)
                    .build())
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NativeObjectiveRecoveryWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build())
        }
    }
}
