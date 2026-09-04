package com.ultron.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

 data class CommandResult(val command: String, val ok: Boolean, val detail: String)

/** Single in-process connection owned by the app. The foreground service and UI use this same instance. */
class UltronClient private constructor(private val store: SecureStore) {
    companion object {
        @Volatile private var instance: UltronClient? = null
        fun get(context: Context): UltronClient = instance ?: synchronized(this) {
            instance ?: UltronClient(SecureStore(context.applicationContext)).also { instance = it }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    private val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val resultListeners = CopyOnWriteArrayList<(CommandResult) -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    fun addStateListener(l: (String) -> Unit) { stateListeners.add(l) }
    fun removeStateListener(l: (String) -> Unit) { stateListeners.remove(l) }
    fun addResultListener(l: (CommandResult) -> Unit) { resultListeners.add(l) }
    fun removeResultListener(l: (CommandResult) -> Unit) { resultListeners.remove(l) }
    fun addLogListener(l: (String) -> Unit) { logListeners.add(l) }
    fun removeLogListener(l: (String) -> Unit) { logListeners.remove(l) }
    private var reconnectAttempt = 0
    @Volatile private var reconnectEnabled = true

    private fun log(message: String) { logListeners.forEach { it(message) } }
    private fun state(value: String) { stateListeners.forEach { it(value) } }
    private fun result(value: CommandResult) { resultListeners.forEach { it(value) } }
    private fun safeHost(host: String) = host.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    suspend fun pair(host: String, code: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val h = safeHost(host)
            require(h.isNotBlank()) { "PC host is required" }
            val body = JSONObject().put("code", code).put("name", name).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("http://$h:8766/pair/confirm").post(body).build()
            log("Pairing request sent to $h")
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException(JSONObject(text).optString("error", "Pairing failed (${r.code})"))
                val j = JSONObject(text)
                store.savePair(h, j.getString("device_id"), j.getString("token"))
                log("Pairing successful; credentials stored securely")
            }
        }
    }

    suspend fun commands(): Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        val h = safeHost(store.pcHost); val id = store.deviceId ?: error("Not paired")
        val req = Request.Builder().url("http://$h:8766/devices/$id/commands").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val a = JSONObject(text).getJSONArray("granted_commands")
            (0 until a.length()).map { a.getString(it) }
        }
    }}

    @Synchronized fun connect() {
        val host = safeHost(store.pcHost); val id = store.deviceId; val tok = store.token
        if (host.isBlank() || id == null || tok == null) { state("disconnected"); return }
        reconnectEnabled = true
        if (ws != null) return
        state("reconnecting")
        log("Connecting to ws://$host:8765")
        val req = Request.Builder().url("ws://$host:8765").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, response: Response) {
                reconnectAttempt = 0
                log("WebSocket connected; authenticating")
                w.send(JSONObject().put("type", "auth").put("device_id", id).put("token", tok).toString())
            }
            override fun onMessage(w: WebSocket, text: String) { runCatching {
                val j = JSONObject(text)
                when (j.optString("type")) {
                    "auth_ok" -> { state("connected"); log("Authenticated") }
                    "command_result" -> result(CommandResult(j.optString("command"), j.optBoolean("ok"), j.optString("detail")))
                }
            }.onFailure { log("Malformed server message ignored") } }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                ws = null; state("disconnected"); log("WebSocket failure: ${t.javaClass.simpleName}"); scheduleReconnect()
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                ws = null; state("disconnected"); log("WebSocket closed ($code)"); scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled || store.deviceId == null) return
        val delay = min(1000L * (1L shl reconnectAttempt.coerceAtMost(5)), 30000L)
        reconnectAttempt++
        log("Reconnecting in ${delay / 1000}s")
        Thread { Thread.sleep(delay); if (reconnectEnabled) connect() }.start()
    }

    fun sendCommand(name: String, args: Map<String, String> = emptyMap()): Boolean {
        val j = JSONObject().put("type", "remote_command").put("command", name)
        val a = JSONObject(); args.forEach { (k, v) -> a.put(k, v) }; j.put("args", a)
        val sent = ws?.send(j.toString()) == true
        if (sent) log("Command requested: $name") else log("Command not sent: disconnected")
        return sent
    }

    fun close() { reconnectEnabled = false; ws?.close(1000, "user"); ws = null; state("disconnected"); log("Connection stopped") }
}
