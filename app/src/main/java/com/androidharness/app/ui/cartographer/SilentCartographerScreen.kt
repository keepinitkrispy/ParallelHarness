package com.androidharness.app.ui.cartographer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val ROOM_BASE = "http://127.0.0.1:49174"

private data class RoomEvent(
    val id: String,
    val time: Long,
    val speaker: String,
    val text: String,
    val kind: String,
    val status: String,
)

private val roomJson = Json { ignoreUnknownKeys = true }
private val roomHttp = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()

@Composable
fun SilentCartographerScreen(
    onOpenDrawer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<RoomEvent>() }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { fetchEvents() }
                .onSuccess { fresh ->
                    connected = true
                    error = null
                    if (fresh != events.toList()) {
                        events.clear()
                        events.addAll(fresh)
                    }
                }
                .onFailure {
                    connected = false
                    error = it.message ?: "Bridge unavailable"
                }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { fetchEvents() }
                .onSuccess { fresh ->
                    connected = true
                    error = null
                    if (fresh != events.toList()) {
                        events.clear()
                        events.addAll(fresh)
                    }
                }
                .onFailure {
                    connected = false
                    error = it.message ?: "Bridge unavailable"
                }
            delay(1_200)
        }
    }

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Silent Cartographer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (connected) "Ryan · ChatGPT · Claude" else "Bridge offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (sending) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                    }
                    if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message the room…") },
                        enabled = !sending,
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = draft.isNotBlank() && !sending,
                        onClick = {
                            val text = draft.trim()
                            if (text.isEmpty()) return@IconButton
                            draft = ""
                            sending = true
                            error = null
                            scope.launch {
                                runCatching { postMessage(text) }
                                    .onFailure { error = it.message ?: "Send failed" }
                                sending = false
                                refresh()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        if (events.isEmpty() && connected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "The room is ready. Say something.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp,
                    vertical = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    RoomBubble(event)
                }
            }
        }
    }
}

@Composable
private fun RoomBubble(event: RoomEvent) {
    val mine = event.speaker == "Ryan"
    val scheme = MaterialTheme.colorScheme
    val bubble = when (event.speaker) {
        "Ryan" -> scheme.primaryContainer
        "ChatGPT" -> scheme.secondaryContainer
        "Claude" -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when (event.speaker) {
        "Ryan" -> scheme.onPrimaryContainer
        "ChatGPT" -> scheme.onSecondaryContainer
        "Claude" -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubble,
            contentColor = content,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 5.dp,
                bottomEnd = if (mine) 5.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 350.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.speaker,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (event.status != "observed") {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            event.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.72f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(event.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private suspend fun fetchEvents(): List<RoomEvent> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("$ROOM_BASE/events").get().build()
    roomHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Bridge returned " + response.code)
        val body = response.body?.string().orEmpty()
        val root = roomJson.parseToJsonElement(body).jsonObject
        root["events"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RoomEvent(
                id = id,
                time = obj["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                speaker = obj["speaker"]?.jsonPrimitive?.content ?: "Unknown",
                text = obj["text"]?.jsonPrimitive?.content.orEmpty(),
                kind = obj["kind"]?.jsonPrimitive?.content ?: "message",
                status = obj["status"]?.jsonPrimitive?.content ?: "observed",
            )
        }
    }
}

private suspend fun postMessage(text: String) = withContext(Dispatchers.IO) {
    val jsonBody = roomJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
    val body = ("{\"text\":" + jsonBody + "}")
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url("$ROOM_BASE/user-send")
        .post(body)
        .build()
    roomHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val detail = response.body?.string().orEmpty().take(240)
            val suffix = if (detail.isBlank()) "" else ": " + detail
            error("Bridge send failed (" + response.code + ")" + suffix)
        }
    }
}
