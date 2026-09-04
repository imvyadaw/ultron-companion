package com.ultron.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        setContent { App(UltronClient.get(applicationContext)) }
    }
}

@Composable fun App(client: UltronClient) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope(); var tab by remember { mutableStateOf(0) }
    var paired by remember { mutableStateOf(SecureStore(context).deviceId != null) }
    var state by remember { mutableStateOf("disconnected") }; var commands by remember { mutableStateOf(emptyList<String>()) }
    var last by remember { mutableStateOf<CommandResult?>(null) }; val logs = remember { mutableStateListOf<String>() }
    DisposableEffect(client) {
        val stateListener: (String) -> Unit = { state = it }; val resultListener: (CommandResult) -> Unit = { last = it }; val logListener: (String) -> Unit = { msg -> if (logs.size >= 200) logs.removeFirst(); logs.add(msg) }
        client.addStateListener(stateListener); client.addResultListener(resultListener); client.addLogListener(logListener)
        onDispose { client.removeStateListener(stateListener); client.removeResultListener(resultListener); client.removeLogListener(logListener) }
    }
    LaunchedEffect(paired) { if (paired) { client.connect(); commands = client.commands().getOrDefault(emptyList()) } }
    MaterialTheme {
        Scaffold(bottomBar = { NavigationBar { listOf("Pair", "Commands", "Settings").forEachIndexed { i, t -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) }) } } }) { p ->
            Box(Modifier.padding(p).padding(16.dp)) { when (tab) {
                0 -> PairScreen(client, { paired = true; ContextCompat.startForegroundService(context, Intent(context, UltronConnectionService::class.java)) })
                1 -> CommandScreen(commands, state, last) { client.sendCommand(it) }
                2 -> SettingsScreen(client, logs) { client.close(); SecureStore(context).clearPair(); paired = false; commands = emptyList(); tab = 0 }
            } }
        }
    }
}

@Composable fun PairScreen(client: UltronClient, onPaired: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SecureStore(context) }; var host by remember { mutableStateOf(store.pcHost) }; var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL) }; var msg by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Pair with ULTRON", style = MaterialTheme.typography.headlineSmall); Text("Enter the PC LAN IP and the 6-digit code shown by ULTRON.")
        OutlinedTextField(host, { host = it }, label = { Text("PC LAN IP / hostname") }, singleLine = true)
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit pairing code") }, singleLine = true)
        OutlinedTextField(name, { name = it }, label = { Text("Phone name") }, singleLine = true)
        Button(enabled = !busy && host.isNotBlank() && code.length == 6, onClick = { busy = true; scope.launch { val r = client.pair(host, code, name); busy = false; msg = r.exceptionOrNull()?.message ?: "Paired successfully"; if (r.isSuccess) onPaired() } }) { Text(if (busy) "Pairing…" else "Pair") }
        if (msg.isNotBlank()) Text(msg)
    }
}

@Composable fun CommandScreen(commands: List<String>, state: String, last: CommandResult?, send: (String) -> Unit) {
    Column { Text("Commands", style = MaterialTheme.typography.headlineSmall); Text("Connection: $state"); Spacer(Modifier.height(12.dp))
        if (commands.isEmpty()) Text("No commands are currently granted to this device.")
        LazyColumn { items(commands) { c -> Card(onClick = { send(c) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(c, Modifier.padding(16.dp)) } } }
        last?.let { Spacer(Modifier.height(12.dp)); Text("Result: ${it.command} — ${if (it.ok) "OK" else "FAILED"}"); if (it.detail.isNotBlank()) Text(it.detail) }
    }
}

@Composable fun SettingsScreen(client: UltronClient, logs: List<String>, unpair: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current; val store = remember { SecureStore(context) }; var host by remember { mutableStateOf(store.pcHost) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineSmall); OutlinedTextField(host, { host = it; store.pcHost = it }, label = { Text("PC IP / hostname") })
        Text("Device ID: ${store.deviceId?.let { "stored securely" } ?: "not paired"}"); Button(onClick = unpair) { Text("Unpair (clear local credentials)") }
        Text("Connection log", style = MaterialTheme.typography.titleMedium); LazyColumn(Modifier.heightIn(max = 260.dp)) { items(logs) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
