package com.androidharness.app.notifications

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

class NotificationBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val listener = HarnessNotificationListener.instance
        if (listener == null) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "notification listener not connected"
            return
        }

        when (intent.action) {
            ACTION_LIST -> {
                val out = JSONArray()
                listener.snapshot().forEach { n ->
                    val actions = JSONArray()
                    n.actions.forEach { a ->
                        actions.put(JSONObject()
                            .put("index", a.index)
                            .put("title", a.title)
                            .put("replyCapable", a.replyCapable))
                    }
                    out.put(JSONObject()
                        .put("key", n.key)
                        .put("packageName", n.packageName)
                        .put("title", n.title)
                        .put("text", n.text)
                        .put("actions", actions))
                }
                resultCode = Activity.RESULT_OK
                resultData = out.toString()
            }

            ACTION_TRIGGER -> {
                val key = intent.getStringExtra("key").orEmpty()
                val index = intent.getIntExtra("index", -1)
                val reply = if (intent.hasExtra("reply")) intent.getStringExtra("reply") else null
                val result = listener.perform(key, index, reply)
                result.fold(
                    onSuccess = {
                        resultCode = Activity.RESULT_OK
                        resultData = it
                    },
                    onFailure = {
                        resultCode = Activity.RESULT_CANCELED
                        resultData = it.message ?: it::class.java.simpleName
                    }
                )
            }

            else -> {
                resultCode = Activity.RESULT_CANCELED
                resultData = "unknown action"
            }
        }
    }

    companion object {
        const val ACTION_LIST = "dev.keepinitkrispy.parallel.NOTIFICATION_LIST"
        const val ACTION_TRIGGER = "dev.keepinitkrispy.parallel.NOTIFICATION_ACTION"
    }
}
