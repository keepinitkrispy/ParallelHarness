package dev.keepinitkrispy.silentcartographer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val ROOM_BASE = "http://127.0.0.1:49174"

private data class RoomEvent(
    val id: String,
    val speaker: String,
    val text: String,
    val status: String,
)

private val roomHttp = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                SilentCartographerApp()
            }
        }
    }
}

@Composable
private fun SilentCartographerApp() {
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<RoomEvent>() }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
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

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(900)
        }
    }

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Silent Cartographer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Ryan · ChatGPT · Claude",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (connected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ) {
                        Text(
                            if (connected) "LIVE" else "OFFLINE",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
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
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message the room…") },
                            enabled = !sending,
                            maxLines = 5,
                            shape = RoundedCornerShape(24.dp),
                        )

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            IconButton(
                                enabled = draft.isNotBlank() && !sending,
                                onClick = {
                                    val text = draft.trim()
                                    if (text.isEmpty()) return@IconButton
                                    sending = true
                                    error = null
                                    scope.launch {
                                        runCatching { postMessage(text) }
                                            .onSuccess {
                                                draft = ""
                                                refresh()
                                            }
                                            .onFailure {
                                                error = it.message ?: "Send failed"
                                            }
                                        sending = false
                                    }
                                },
                            ) {
                                if (sending) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Icon(Icons.Filled.Send, contentDescription = "Send")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (connected) "The room is ready." else "Waiting for the local bridge…",
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
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
            modifier = Modifier.widthIn(max = 360.dp),
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
                            color = content.copy(alpha = 0.68f),
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
    val request = Request.Builder().url(ROOM_BASE + "/events").get().build()
    roomHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Bridge returned " + response.code)
        val root = JSONObject(response.body?.string().orEmpty())
        val array = root.optJSONArray("events") ?: return@use emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("kind", "message") != "message") continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    RoomEvent(
                        id = id,
                        speaker = item.optString("speaker", "Unknown"),
                        text = item.optString("text"),
                        status = item.optString("status", "observed"),
                    )
                )
            }
        }
    }
}

private suspend fun postMessage(text: String) = withContext(Dispatchers.IO) {
    LocalSocket().use { socket ->
        socket.soTimeout = 180_000
        socket.connect(
            LocalSocketAddress(
                "silent_cartographer_ui_v1",
                LocalSocketAddress.Namespace.ABSTRACT,
            )
        )
        val payload = JSONObject().put("text", text).toString() + "\n"
        socket.outputStream.write(payload.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        val response = socket.inputStream.bufferedReader(Charsets.UTF_8).readLine()
            ?: error("Local bridge closed without a response")
        val result = JSONObject(response)
        if (!result.optBoolean("ok")) {
            error(result.optString("error", "Local bridge rejected the message"))
        }
    }
}
