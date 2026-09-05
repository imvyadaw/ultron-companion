package com.ultron.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        setContent { App(UltronClient.get(applicationContext)) }
    }
}

data class ChatLine(val fromUser: Boolean, val text: String)

@Composable
fun App(client: UltronClient) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var paired by remember { mutableStateOf(SecureStore(context).deviceId != null) }
    var state by remember { mutableStateOf("disconnected") }
    var commands by remember { mutableStateOf(emptyList<String>()) }
    var last by remember { mutableStateOf<CommandResult?>(null) }
    var hostStatus by remember { mutableStateOf<HostStatus?>(null) }
    val logs = remember { mutableStateListOf<String>() }
    val chatLines = remember { mutableStateListOf<ChatLine>() }
    var chatPending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts.value?.language = Locale.getDefault() }
            }
        }
        tts.value = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    DisposableEffect(client) {
        val stateListener: (String) -> Unit = { state = it }
        val resultListener: (CommandResult) -> Unit = { last = it }
        val logListener: (String) -> Unit = { msg ->
            if (logs.size >= 200) logs.removeFirst()
            logs.add(msg)
        }
        val chatListener: (ChatReply) -> Unit = { r ->
            chatPending = false
            if (r.ok) {
                chatLines.add(ChatLine(false, r.text))
                val speech = r.spokenText.ifBlank { r.text }
                if (speech.isNotBlank()) {
                    tts.value?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "ultron_reply")
                }
            } else {
                chatLines.add(ChatLine(false, "(error) ${r.error.ifBlank { "ULTRON did not reply" }}"))
            }
        }
        client.addStateListener(stateListener)
        client.addResultListener(resultListener)
        client.addLogListener(logListener)
        client.addChatListener(chatListener)
        onDispose {
            client.removeStateListener(stateListener)
            client.removeResultListener(resultListener)
            client.removeLogListener(logListener)
            client.removeChatListener(chatListener)
        }
    }

    fun refreshCommands() {
        scope.launch {
            client.commands().onSuccess { commands = it }
                .onFailure { logs.add("Command catalog refresh failed: ${it.message.orEmpty()}") }
        }
    }

    LaunchedEffect(paired) {
        if (paired) {
            client.connect()
            refreshCommands()
        }
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Pair", "Chat", "Commands", "Settings").forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {},
                            label = { Text(title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).padding(16.dp)) {
                when (tab) {
                    0 -> PairScreen(client) {
                        paired = true
                        tab = 1
                        ContextCompat.startForegroundService(
                            context, Intent(context, UltronConnectionService::class.java)
                        )
                    }
                    1 -> ChatScreen(
                        state = state,
                        lines = chatLines,
                        pending = chatPending
                    ) { text ->
                        if (text.isNotBlank()) {
                            chatLines.add(ChatLine(true, text))
                            chatPending = client.sendChatMessage(text)
                            if (!chatPending) chatPending = false
                        }
                    }
                    2 -> CommandScreen(
                        commands = commands,
                        state = state,
                        last = last,
                        refresh = ::refreshCommands
                    ) { name, args ->
                        if (!client.sendCommand(name, args)) {
                            last = CommandResult(name, false, "ULTRON is not connected")
                        }
                    }
                    3 -> SettingsScreen(
                        client = client,
                        state = state,
                        hostStatus = hostStatus,
                        logs = logs,
                        refreshStatus = {
                            scope.launch {
                                client.hostStatus()
                                    .onSuccess { hostStatus = it }
                                    .onFailure { logs.add("Host status failed: ${it.message.orEmpty()}") }
                            }
                        }
                    ) {
                        context.stopService(Intent(context, EyesEarsService::class.java))
                        context.stopService(Intent(context, LocationService::class.java))
                        client.close()
                        SecureStore(context).clearPair()
                        paired = false
                        commands = emptyList()
                        hostStatus = null
                        tab = 0
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    state: String,
    lines: List<ChatLine>,
    pending: Boolean,
    send: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scope.launch {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val spoken = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) send(spoken)
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask ULTRON…")
            runCatching { speechLauncher.launch(intent) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Chat with ULTRON", style = MaterialTheme.typography.headlineSmall)
        Text("Connection: $state", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(lines) { line ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Card { Text(line.text, Modifier.padding(10.dp)) }
                }
            }
            if (pending) {
                item {
                    Text(
                        "ULTRON is thinking…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Type a message…") },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.padding(start = 4.dp)
            ) { Text("🎙") }
            OutlinedButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        send(text)
                        input = ""
                    }
                },
                modifier = Modifier.padding(start = 4.dp)
            ) { Text("➤") }
        }
    }
}

@Composable
fun PairScreen(client: UltronClient, onPaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SecureStore(context) }
    var host by remember { mutableStateOf(store.pcHost) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pair with ULTRON", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the PC LAN/Tailscale address and the 6-digit code shown by ULTRON.")

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("PC IP / hostname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6-digit pairing code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Phone name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !busy && host.isNotBlank() && code.length == 6,
            onClick = {
                busy = true
                scope.launch {
                    val r = client.pair(host, code, name)
                    busy = false
                    r.onSuccess {
                        msg = "Paired successfully."
                        onPaired()
                    }.onFailure {
                        msg = it.message ?: "Pairing failed."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Pairing…" else "Pair") }

        if (msg.isNotBlank()) Text(msg)
    }
}

private fun org.json.JSONObject.toStringMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key ->
        when (val v = get(key)) {
            org.json.JSONObject.NULL -> null
            is org.json.JSONObject -> v.toStringMap()
            is org.json.JSONArray -> v.toStringList()
            else -> v
        }
    }

private fun org.json.JSONArray.toStringList(): List<Any?> =
    (0 until length()).map { i ->
        when (val v = get(i)) {
            org.json.JSONObject.NULL -> null
            is org.json.JSONObject -> v.toStringMap()
            is org.json.JSONArray -> v.toStringList()
            else -> v
        }
    }

@Composable
fun CommandScreen(
    commands: List<String>,
    state: String,
    last: CommandResult?,
    refresh: () -> Unit,
    send: (String, Map<String, Any?>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("{}") }
    var selected by remember { mutableStateOf<String?>(null) }

    val filtered = remember(commands, query) {
        if (query.isBlank()) commands
        else commands.filter { it.contains(query, ignoreCase = true) }
    }

    fun parseArgs(): Map<String, Any?>? = runCatching {
        val j = org.json.JSONObject(argsText.ifBlank { "{}" })
        j.keys().asSequence().associateWith { key ->
            when (val value = j.get(key)) {
                org.json.JSONObject.NULL -> null
                is org.json.JSONObject -> value.toStringMap()
                is org.json.JSONArray -> value.toStringList()
                else -> value
            }
        }
    }.getOrNull()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Commands", style = MaterialTheme.typography.headlineSmall)
                Text("Connection: $state • ${commands.size} tools")
            }
            TextButton(onClick = refresh) { Text("Refresh") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search tools…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        if (selected != null) {
            Text("Selected: $selected", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = argsText,
                onValueChange = { argsText = it },
                label = { Text("Args JSON (e.g. {\"path\":\"C:/x\"})") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    parseArgs()?.let { send(selected!!, it) }
                }) { Text("Run") }
                OutlinedButton(onClick = {
                    selected = null
                    argsText = "{}"
                }) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (commands.isEmpty()) {
            Text("No tools available. Check pairing, PC address, and ULTRON connection.")
        }

        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(filtered) { command ->
                Card(
                    onClick = { selected = command; argsText = "{}" },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Text(command, Modifier.padding(14.dp))
                }
            }
        }

        last?.let {
            Spacer(Modifier.height(8.dp))
            Text("Result: ${it.command} — ${if (it.ok) "OK" else "FAILED"}")
            if (it.detail.isNotBlank()) {
                Text(it.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    client: UltronClient,
    state: String,
    hostStatus: HostStatus?,
    logs: List<String>,
    refreshStatus: () -> Unit,
    unpair: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SecureStore(context) }
    var host by remember { mutableStateOf(store.pcHost) }
    var liveSenseOn by remember { mutableStateOf(false) }
    var locationOn by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val camera = grants[Manifest.permission.CAMERA] == true
        val mic = grants[Manifest.permission.RECORD_AUDIO] == true
        if (camera && mic) {
            context.startForegroundService(Intent(context, EyesEarsService::class.java))
            liveSenseOn = true
            permissionMessage = ""
        } else {
            permissionMessage = "Camera and microphone permissions are both required for Eyes & Ears."
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            context.startForegroundService(Intent(context, LocationService::class.java))
            locationOn = true
            permissionMessage = ""
        } else {
            permissionMessage = "Location permission was not granted."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text("Connection: $state", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = refreshStatus) { Text("PC status") }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it; store.pcHost = it },
            label = { Text("PC IP / hostname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        hostStatus?.let {
            Text("${it.hostName} • ${it.deviceCount} paired device(s)")
        }

        Text("Device ID: ${store.deviceId?.let { "stored securely" } ?: "not paired"}")
        Button(onClick = unpair) { Text("Unpair and stop sharing") }

        HorizontalDivider()
        Text("Eyes & Ears", style = MaterialTheme.typography.titleMedium)
        Text(
            if (liveSenseOn)
                "ON • camera frame about every 5s + microphone chunks"
            else
                "OFF • ULTRON can only use phone camera/mic after you start this"
        )
        Button(onClick = {
            if (liveSenseOn) {
                context.stopService(Intent(context, EyesEarsService::class.java))
                liveSenseOn = false
            } else {
                permLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }
        }) { Text(if (liveSenseOn) "Stop Eyes & Ears" else "Start Eyes & Ears") }

        HorizontalDivider()
        Text("Location Sharing", style = MaterialTheme.typography.titleMedium)
        Text(if (locationOn) "ON • GPS updates about every 30s" else "OFF")
