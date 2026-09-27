package com.androidharness.app.tools

import com.androidharness.app.notifications.HarnessNotificationListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class NotificationListTool : Tool {
    override val name = "notification_list"
    override val description =
        "List active Android notifications and their actionable buttons. Shows which actions accept inline reply text."
    override val parametersSchema: JsonObject = Schema.obj(emptyMap())
    override val isReadOnly = true

    override fun isAvailable(ctx: ToolContext): Boolean =
        HarnessNotificationListener.instance != null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val listener = HarnessNotificationListener.instance
            ?: return ToolResult(false, "Notification listener is not connected")
        val rows = buildJsonArray {
            listener.snapshot().forEach { n ->
                add(buildJsonObject {
                    put("key", n.key)
                    put("packageName", n.packageName)
                    put("title", n.title)
                    put("text", n.text)
                    putJsonArray("actions") {
                        n.actions.forEach { a ->
                            add(buildJsonObject {
                                put("index", a.index)
                                put("title", a.title)
                                put("replyCapable", a.replyCapable)
                            })
                        }
                    }
                })
            }
        }
        return ToolResult(true, rows.toString())
    }
}

class NotificationActionTool : Tool {
    override val name = "notification_action"
    override val description =
        "Trigger an action button on an active Android notification. If replyText is supplied, sends it through the notification's inline-reply RemoteInput without opening the app."
    override val parametersSchema: JsonObject = Schema.obj(
        properties = mapOf(
            "notificationKey" to Schema.string("Exact notification key from notification_list."),
            "actionIndex" to Schema.integer("Zero-based action index from notification_list."),
            "replyText" to Schema.string("Optional inline reply text. Omit for non-reply actions."),
        ),
        required = listOf("notificationKey", "actionIndex"),
    )
    override val isReadOnly = false

    override fun isAvailable(ctx: ToolContext): Boolean =
        HarnessNotificationListener.instance != null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val key = args["notificationKey"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "notificationKey is required")
        val index = args["actionIndex"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "actionIndex must be an integer")
        val reply = args["replyText"]?.jsonPrimitive?.content
        val listener = HarnessNotificationListener.instance
            ?: return ToolResult(false, "Notification listener is not connected")
        return listener.perform(key, index, reply).fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, it.message ?: it::class.java.simpleName) },
        )
    }
}
