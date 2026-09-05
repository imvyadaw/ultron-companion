package com.ultron.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class CommandResult(val command: String, val ok: Boolean, val detail: String)
data class ChatReply(val ok: Boolean, val text: String, val spokenText: String, val error: String)
data class HostStatus(val hostName: String, val uptimeSeconds: Double, val deviceCount: Int)

/**
 * Process-wide client shared by the UI and foreground services.
 *
 * Pairing is HTTP on the user's LAN/VPN because the PC companion API currently
 * exposes HTTP. Live traffic uses the authenticated WebSocket on port 8765.
 * The app never accepts arbitrary inbound commands: the phone only sends
 * messages after the user explicitly starts an action in the UI/service.
 */
class UltronClient private constructor(private val store: SecureStore) {
    companion object {
        @Volatile private var instance: UltronClient? = null
        fun get(context: Context): UltronClient = instance ?: synchronized(this) {
            instance ?: UltronClient(SecureStore(context.applicationContext)).also { instance = it }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var authenticated = false

    private val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val resultListeners = CopyOnWriteArrayList<(CommandResult) -> Unit>()
    private val chatListeners = CopyOnWriteArrayList<(ChatReply) -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var reconnectAttempt = 0
    @Volatile private var reconnectEnabled = true

    fun addStateListener(l: (String) -> Unit) = stateListeners.add(l)
    fun removeStateListener(l: (String) -> Unit) = stateListeners.remove(l)
    fun addResultListener(l: (CommandResult) -> Unit) = resultListeners.add(l)
    fun removeResultListener(l: (CommandResult) -> Unit) = resultListeners.remove(l)
    fun addChatListener(l: (ChatReply) -> Unit) = chatListeners.add(l)
    fun removeChatListener(l: (ChatReply) -> Unit) = chatListeners.remove(l)
    fun addLogListener(l: (String) -> Unit) = logListeners.add(l)
    fun removeLogListener(l: (String) -> Unit) = logListeners.remove(l)

    private fun log(message: String) = logListeners.forEach { it(message) }
    private fun state(value: String) = stateListeners.forEach { it(value) }
    private fun result(value: CommandResult) = resultListeners.forEach { it(value) }
    private fun chatReply(value: ChatReply) = chatListeners.forEach { it(value) }

    private fun safeHost(raw: String): String {
        var h = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removeSuffix("/")
            .trim()
        // The UI accepts a hostname/IP, not a URL. Strip accidental paths.
        h = h.substringBefore('/')
        return h
    }

    private fun httpUrl(host: String, path: String) = "http://${safeHost(host)}:8766$path"
    private fun wsUrl(host: String) = "ws://${safeHost(host)}:8765"

    suspend fun pair(host: String, code: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val h = safeHost(host)
                require(h.isNotBlank()) { "PC host is required" }
                require(code.matches(Regex("\\d{6}"))) { "Pairing code must be 6 digits" }

                val body = JSONObject()
                    .put("code", code)
                    .put("name", name.trim().ifBlank { "Android Companion" })
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val req = Request.Builder().url(httpUrl(h, "/pair/confirm")).post(body).build()
                log("Pairing request sent to $h")

                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    val parsed = runCatching { JSONObject(text) }.getOrNull()
                    if (!r.isSuccessful) {
                        throw IllegalStateException(
                            parsed?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Pairing failed (HTTP ${r.code})"
                        )
                    }
                    val id = parsed?.optString("device_id").orEmpty()
                    val token = parsed?.optString("token").orEmpty()
                    require(id.isNotBlank() && token.isNotBlank()) { "Server returned incomplete pairing credentials" }
                    store.savePair(h, id, token)
                    log("Pairing successful; credentials stored securely")
                }
            }
        }

    suspend fun hostStatus(): Result<HostStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val h = safeHost(store.pcHost)
            require(h.isNotBlank()) { "PC host is not configured" }
            val req = Request.Builder().url(httpUrl(h, "/status")).get().build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val j = JSONObject(text)
                HostStatus(
                    j.optString("host_name", "ULTRON"),
                    j.optDouble("uptime_seconds", 0.0),
                    j.optInt("device_count", 0)
                )
            }
        }
    }

    suspend fun commands(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val h = safeHost(store.pcHost)
            val id = store.deviceId ?: error("Not paired")
            require(h.isNotBlank()) { "PC host is not configured" }

            val req = Request.Builder().url(httpUrl(h, "/devices/$id/commands")).get().build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val a = JSONObject(text).getJSONArray("granted_commands")
                (0 until a.length()).map { a.getString(it) }
            }
        }
    }

    @Synchronized
    fun connect() {
        val host = safeHost(store.pcHost)
        val id = store.deviceId
        val tok = store.token
        if (host.isBlank() || id.isNullOrBlank() || tok.isNullOrBlank()) {
            authenticated = false
            state("disconnected")
            return
        }
        reconnectEnabled = true
        if (ws != null) return

        authenticated = false
        state("connecting")
        log("Connecting to ${wsUrl(host)}")

        val req = Request.Builder().url(wsUrl(host)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, response: Response) {
                reconnectAttempt = 0
                log("WebSocket connected; authenticating")
                w.send(JSONObject()
                    .put("type", "auth")
                    .put("device_id", id)
                    .put("token", tok)
                    .toString())
            }

            override fun onMessage(w: WebSocket, text: String) {
                runCatching {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "auth_ok" -> {
                            authenticated = true
                            state("connected")
                            log("Authenticated")
                        }
                        "command_result" -> result(
                            CommandResult(
                                j.optString("command"),
                                j.optBoolean("ok"),
                                j.optString("detail")
                            )
                        )
                        "chat_reply" -> chatReply(
                            ChatReply(
                                j.optBoolean("ok"),
                                j.optString("text"),
                                j.optString("spoken_text"),
                                j.optString("error")
                            )
                        )
                    }
                }.onFailure { log("Malformed server message ignored") }
            }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                authenticated = false
                ws = null
                state("disconnected")
                log("WebSocket failure: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
                scheduleReconnect()
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                authenticated = false
                ws = null
                state("disconnected")
                log("WebSocket closed ($code): $reason")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled || store.deviceId == null) return
        val delay = min(1000L * (1L shl reconnectAttempt.coerceAtMost(5)), 30_000L)
        reconnectAttempt++
        log("Reconnecting in ${delay / 1000}s")
        Thread {
            try {
                Thread.sleep(delay)
                if (reconnectEnabled) connect()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    fun isConnected(): Boolean = authenticated && ws != null

    fun sendCommand(name: String, args: Map<String, Any?> = emptyMap()): Boolean {
        if (!isConnected()) {
            log("Command not sent: not authenticated")
            return false
        }
        val j = JSONObject().put("type", "remote_command").put("command", name)
        val a = JSONObject()
        args.forEach { (k, v) -> a.put(k, v ?: JSONObject.NULL) }
        j.put("args", a)
        val sent = ws?.send(j.toString()) == true
        if (sent) log("Command requested: $name") else log("Command not sent: socket unavailable")
        return sent
    }

    fun sendChatMessage(text: String): Boolean {
        if (text.isBlank()) return false
        if (!isConnected()) {
            log("Chat not sent: not connected")
            chatReply(ChatReply(false, "", "", "ULTRON is not connected"))
            return false
        }
        val sent = ws?.send(JSONObject().put("type", "chat_message").put("text", text.trim()).toString()) == true
        if (sent) log("Chat sent") else log("Chat not sent: socket unavailable")
        if (!sent) chatReply(ChatReply(false, "", "", "Message could not be sent"))
        return sent
    }

    fun sendVisionFrame(base64Jpeg: String): Boolean {
        if (!isConnected() || base64Jpeg.isBlank()) return false
        return ws?.send(
            JSONObject().put("type", "vision_frame").put("data", base64Jpeg).put("format", "jpeg").toString()
        ) == true
    }

    fun sendAudioChunk(base64Pcm16: String, sampleRate: Int): Boolean {
        if (!isConnected() || base64Pcm16.isBlank()) return false
        return ws?.send(
            JSONObject().put("type", "audio_chunk")
                .put("data", base64Pcm16)
                .put("sample_rate", sampleRate)
                .put("format", "pcm16").toString()
        ) == true
    }

    fun sendLocationUpdate(lat: Double, lon: Double, accuracyM: Float?): Boolean {
        if (!isConnected()) return false
        val j = JSONObject().put("type", "location_update").put("lat", lat).put("lon", lon)
        if (accuracyM != null) j.put("accuracy_m", accuracyM.toDouble())
        return ws?.send(j.toString()) == true
    }

    fun close() {
        reconnectEnabled = false
        authenticated = false
        ws?.close(1000, "user")
        ws = null
        state("disconnected")
        log("Connection stopped")
    }
}
