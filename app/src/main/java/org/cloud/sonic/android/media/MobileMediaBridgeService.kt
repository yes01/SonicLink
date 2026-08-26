package org.cloud.sonic.android.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.cloud.sonic.android.R
import org.cloud.sonic.android.repository.PlatformBindingCredential
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MobileMediaBridgeService : Service() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()
    private lateinit var repository: PlatformSyncRepository
    private lateinit var catalog: RemoteMediaCatalog

    override fun onCreate() {
        super.onCreate()
        repository = PlatformSyncRepository(this)
        catalog = RemoteMediaCatalog(this)
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FORCE, false) == true) {
            reconnectJobs.values.forEach { it.cancel() }
            reconnectJobs.clear()
            heartbeatJobs.values.forEach { it.cancel() }
            heartbeatJobs.clear()
            sockets.values.forEach { it.close(1000, "media credentials refreshed") }
            sockets.clear()
        }
        syncConnections()
        return START_STICKY
    }

    override fun onDestroy() {
        reconnectJobs.values.forEach { it.cancel() }
        heartbeatJobs.values.forEach { it.cancel() }
        sockets.values.forEach { it.close(1000, "media bridge stopped") }
        sockets.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncConnections() {
        val credentials = repository.getBindingCredentials()
        val activeKeys = credentials.map { it.config.bindingKey }.toSet()
        sockets.keys.filterNot(activeKeys::contains).forEach { key -> sockets.remove(key)?.close(1000, "binding removed") }
        if (credentials.isEmpty()) {
            stopSelf()
            return
        }
        credentials.filterNot { sockets.containsKey(it.config.bindingKey) }.forEach(::connect)
    }

    private fun connect(credential: PlatformBindingCredential) {
        val config = credential.config
        val request = Request.Builder()
            .url(mediaWebSocketUrl(config.serverUrl))
            .header("X-Mobile-Device-Id", config.deviceId)
            .header("X-Mobile-Device-Token", credential.deviceToken)
            .build()
        sockets[config.bindingKey] = client.newWebSocket(request, listener(credential))
    }

    private fun listener(credential: PlatformBindingCredential) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sockets[credential.config.bindingKey] !== webSocket) {
                webSocket.close(1000, "stale media connection")
                return
            }
            reconnectJobs.remove(credential.config.bindingKey)?.cancel()
            sendStatus(webSocket, credential, "register")
            heartbeatJobs[credential.config.bindingKey] = scope.launch {
                while (isActive && sockets[credential.config.bindingKey] === webSocket) {
                    delay(20_000)
                    sendStatus(webSocket, credential, "heartbeat")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleCommand(webSocket, credential, text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected(credential, webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected(credential, webSocket)
    }

    private suspend fun handleCommand(webSocket: WebSocket, credential: PlatformBindingCredential, text: String) {
        val envelope = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val requestId = envelope.string("requestId")
        val payload = envelope.getAsJsonObject("payload") ?: JsonObject()
        val result = runCatching {
            when (envelope.string("type")) {
                "media_capabilities" -> JsonObject().apply { addProperty("success", true); add("capabilities", catalog.capabilities()) }
                "media_list" -> catalog.list(credential.config.bindingKey, payload)
                "media_thumbnail" -> catalog.thumbnail(credential.config.bindingKey, payload)
                "media_attach" -> catalog.attach(credential.config.bindingKey, payload)
                else -> JsonObject().apply { addProperty("success", false); addProperty("code", "unsupported_command"); addProperty("message", "不支持的媒体命令") }
            }
        }.getOrElse { error ->
            JsonObject().apply {
                addProperty("success", false)
                addProperty("code", "media_command_failed")
                addProperty("message", error.message ?: "媒体命令执行失败")
            }
        }
        webSocket.send(gson.toJson(JsonObject().apply {
            addProperty("type", "command_response")
            addProperty("requestId", requestId)
            addProperty("deviceId", credential.config.deviceId)
            add("payload", result)
        }))
    }

    private fun sendStatus(webSocket: WebSocket, credential: PlatformBindingCredential, type: String) {
        webSocket.send(gson.toJson(JsonObject().apply {
            addProperty("type", type)
            addProperty("deviceId", credential.config.deviceId)
            add("payload", JsonObject().apply {
                addProperty("deviceName", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                addProperty("androidVersion", android.os.Build.VERSION.RELEASE)
                add("capabilities", catalog.capabilities())
            })
        }))
    }

    private fun disconnected(credential: PlatformBindingCredential, socket: WebSocket) {
        if (!sockets.remove(credential.config.bindingKey, socket)) return
        heartbeatJobs.remove(credential.config.bindingKey)?.cancel()
        if (repository.getBindings().none { it.bindingKey == credential.config.bindingKey && it.isBound }) return
        reconnectJobs[credential.config.bindingKey]?.cancel()
        reconnectJobs[credential.config.bindingKey] = scope.launch {
            delay(5_000)
            if (!sockets.containsKey(credential.config.bindingKey)) connect(credential)
        }
    }

    private fun mediaWebSocketUrl(serverUrl: String): String {
        val uri = URI(serverUrl)
        val scheme = if (uri.scheme.equals("https", true)) "wss" else "ws"
        return "$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty().trimEnd('/')}/api/v1/bug-submit/mobile/media/ws"
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.logo)
            .setContentTitle(getString(R.string.mobile_media_bridge_title))
            .setContentText(getString(R.string.mobile_media_bridge_active))
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.mobile_media_bridge_title), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    companion object {
        private const val CHANNEL_ID = "sonic_link_mobile_media"
        private const val NOTIFICATION_ID = 2026

        fun sync(context: Context, force: Boolean = false) {
            val appContext = context.applicationContext
            val hasBindings = PlatformSyncRepository(appContext).getBindings().any { it.isBound }
            val intent = Intent(appContext, MobileMediaBridgeService::class.java).putExtra(EXTRA_FORCE, force)
            if (!hasBindings) {
                appContext.stopService(intent)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent) else appContext.startService(intent)
        }

        private const val EXTRA_FORCE = "force"
    }
}
