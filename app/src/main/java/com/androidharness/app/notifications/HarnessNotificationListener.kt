package com.androidharness.app.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NotificationActionSnapshot(
    val index: Int,
    val title: String,
    val replyCapable: Boolean,
)

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val actions: List<NotificationActionSnapshot>,
)

class HarnessNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: HarnessNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        requestRebind(ComponentName(this, HarnessNotificationListener::class.java))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun snapshot(): List<NotificationSnapshot> =
        activeNotifications?.map { sbn ->
            val n = sbn.notification
            val actions = n.actions?.mapIndexed { index, action ->
                NotificationActionSnapshot(
                    index = index,
                    title = action.title?.toString().orEmpty(),
                    replyCapable = !action.remoteInputs.isNullOrEmpty(),
                )
            }.orEmpty()
            NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName.orEmpty(),
                title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = (
                    n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                        ?: n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    )?.toString().orEmpty(),
                actions = actions,
            )
        }.orEmpty()

    fun perform(notificationKey: String, actionIndex: Int, replyText: String?): Result<String> = runCatching {
        val sbn: StatusBarNotification = activeNotifications
            ?.firstOrNull { it.key == notificationKey }
            ?: error("Notification is no longer active")
        val action = sbn.notification.actions?.getOrNull(actionIndex)
            ?: error("Action index $actionIndex is not available")
        val inputs = action.remoteInputs

        if (replyText != null) {
            if (inputs.isNullOrEmpty()) error("This action does not accept inline reply text")
            val fillIn = Intent()
            val results = android.os.Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, replyText) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            action.actionIntent.send(this, 0, fillIn)
            "Replied via notification action: ${action.title}"
        } else {
            action.actionIntent.send()
            "Triggered notification action: ${action.title}"
        }
    }.recoverCatching { t ->
        if (t is PendingIntent.CanceledException) error("Notification action expired")
        throw t
    }
}
