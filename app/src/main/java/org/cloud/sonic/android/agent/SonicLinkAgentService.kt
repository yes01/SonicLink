package org.cloud.sonic.android.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.cloud.sonic.android.MainActivity
import org.cloud.sonic.android.R
import org.cloud.sonic.android.ScreenCaptureState
import org.cloud.sonic.android.accessibility.SonicLinkAccessibilityState
import org.cloud.sonic.android.accessibility.SonicLinkControlResult
import org.cloud.sonic.android.accessibility.SonicLinkTouchStroke
import org.cloud.sonic.android.utils.SLog
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class SonicLinkAgentService : Service() {
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var configStore: SonicLinkConfigStore
    private lateinit var deviceId: String

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private lateinit var screenStreamer: SonicLinkScreenStreamer
    private var shouldRun = false
    private var isStopping = false
    private var reconnectAttempt = 0

    override fun onCreate() {
        super.onCreate()
        configStore = SonicLinkConfigStore(this)
        deviceId = configStore.getOrCreateDeviceId()
        screenStreamer = SonicLinkScreenStreamer(this, serviceScope) { webSocket }
        SonicLinkStatus.serviceRunning = true
        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            return START_NOT_STICKY
        }
        shouldRun = true
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAgent(stopService = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        val config = configStore.getConfig()
        if (!config.isReady) {
            SonicLinkStatus.connectionState = SonicLinkConnectionState.ERROR
            SonicLinkStatus.lastError = getString(R.string.sonic_link_error_missing_ws)
            notifyStatusChanged()
            stopSelf()
            return
        }

        reconnectJob?.cancel()
        webSocket?.cancel()
        SonicLinkStatus.connectionState = SonicLinkConnectionState.CONNECTING
        SonicLinkStatus.lastError = null
        notifyStatusChanged()
        updateNotification()

        val request = Request.Builder()
            .url(config.webSocketUrl)
            .apply {
                if (config.token.isNotBlank()) {
                    header("Authorization", "Bearer ${config.token}")
                }
                header("X-SonicLink-Device-Id", deviceId)
            }
            .build()
        webSocket = okHttpClient.newWebSocket(request, listener(config))
    }

    private fun listener(config: SonicLinkConfig): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@SonicLinkAgentService.webSocket = webSocket
                reconnectAttempt = 0
                SonicLinkStatus.connectionState = SonicLinkConnectionState.CONNECTED
                SonicLinkStatus.lastConnectedAt = System.currentTimeMillis()
                SonicLinkStatus.lastError = null
                sendEnvelope("register", payload = SonicLinkDeviceInfo.collect(this@SonicLinkAgentService, config))
                startHeartbeat()
                notifyStatusChanged()
                updateNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnected("closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnected(t.message ?: "websocket failure")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && shouldRun) {
                SonicLinkStatus.lastHeartbeatAt = System.currentTimeMillis()
                sendEnvelope(
                    type = "heartbeat",
                    payload = SonicLinkDeviceInfo.collect(this@SonicLinkAgentService, configStore.getConfig())
                )
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun handleDisconnected(reason: String) {
        heartbeatJob?.cancel()
        if (!shouldRun) {
            return
        }
        SonicLinkStatus.connectionState = SonicLinkConnectionState.RECONNECTING
        SonicLinkStatus.lastError = reason
        notifyStatusChanged()
        updateNotification()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = reconnectDelay()
            delay(delayMs)
            if (shouldRun) {
                connect()
            }
        }
    }

    private fun reconnectDelay(): Long {
        val attempt = reconnectAttempt.coerceAtMost(MAX_RECONNECT_EXPONENT)
        reconnectAttempt += 1
        return (1_000L * 2.0.pow(attempt)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun handleMessage(text: String) {
        serviceScope.launch {
            val envelope = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (envelope == null) {
                sendError(null, "invalid_json", "message is not a JSON object")
                return@launch
            }
            val type = envelope.string("type")
            val requestId = envelope.string("requestId")
            val payload = envelope.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

            when (type) {
                "tap" -> respond(requestId, executeTap(payload))
                "long_press" -> respond(requestId, executeLongPress(payload))
                "swipe", "drag" -> respond(requestId, executeSwipe(payload))
                "multi_touch" -> respond(requestId, executeMultiTouch(payload))
                "global_action" -> respond(requestId, executeGlobalAction(payload))
                "input_text", "set_text" -> respond(requestId, executeSetText(payload))
                "start_stream" -> respond(requestId, startStream(payload))
                "stop_stream" -> {
                    val result = screenStreamer.stop()
                    updateForegroundServiceType(includeMediaProjection = false)
                    respond(requestId, result)
                }
                "get_status" -> sendEnvelope("status", requestId, SonicLinkDeviceInfo.collect(this@SonicLinkAgentService, configStore.getConfig()))
                else -> sendError(requestId, "unsupported_command", "unsupported command: $type")
            }
        }
    }

    private fun executeTap(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val x = payload.float("x")
        val y = payload.float("y")
        val validation = validatePoint(x, y)
        if (validation != null) return validation
        return service.tap(x, y)
    }

    private fun executeLongPress(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val x = payload.float("x")
        val y = payload.float("y")
        val validation = validatePoint(x, y)
        if (validation != null) return validation
        return service.longPress(x, y, payload.long("durationMs", 800L))
    }

    private fun executeSwipe(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val startX = payload.float("startX")
        val startY = payload.float("startY")
        val endX = payload.float("endX")
        val endY = payload.float("endY")
        validatePoint(startX, startY)?.let { return it }
        validatePoint(endX, endY)?.let { return it }
        return service.swipe(startX, startY, endX, endY, payload.long("durationMs", 300L))
    }

    private fun executeMultiTouch(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val array = payload.getAsJsonArray("strokes")
            ?: return SonicLinkControlResult.failure("invalid_payload", "multi_touch payload requires strokes")
        val strokes = mutableListOf<SonicLinkTouchStroke>()
        for (item in array) {
            if (!item.isJsonObject) {
                return SonicLinkControlResult.failure("invalid_payload", "multi_touch stroke must be an object")
            }
            val point = item.asJsonObject
            val startX = point.float("startX")
            val startY = point.float("startY")
            val endX = point.float("endX")
            val endY = point.float("endY")
            if (validatePoint(startX, startY) != null || validatePoint(endX, endY) != null) {
                return SonicLinkControlResult.failure("coordinate_out_of_bounds", "one or more touch points are outside the current screen")
            } else {
                strokes.add(SonicLinkTouchStroke(
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    startTimeMs = point.long("startTimeMs", 0L),
                    durationMs = point.long("durationMs", 300L)
                ))
            }
        }
        return service.multiTouch(strokes)
    }

    private fun executeGlobalAction(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        return service.globalAction(payload.string("action"))
    }

    private fun executeSetText(payload: JsonObject): SonicLinkControlResult {
        val service = accessibilityService() ?: return accessibilityUnavailable()
        return service.setText(payload.string("text"))
    }

    private fun startStream(payload: JsonObject): SonicLinkControlResult {
        if (!ScreenCaptureState.hasPermission) {
            return SonicLinkControlResult.failure("screen_permission_missing", "screen capture permission is not granted")
        }
        val config = SonicLinkScreenStreamer.StreamConfig(
            width = payload.int("width", 0),
            height = payload.int("height", 0),
            bitRate = payload.int("bitRate", 2_000_000),
            frameRate = payload.int("frameRate", 20),
            iFrameIntervalSeconds = payload.int("iFrameIntervalSeconds", 1)
        )
        updateForegroundServiceType(includeMediaProjection = true)
        return screenStreamer.start(config)
    }

    private fun accessibilityService() = SonicLinkAccessibilityState.service

    private fun accessibilityUnavailable(): SonicLinkControlResult {
        return SonicLinkControlResult.failure("accessibility_disabled", "SonicLink accessibility service is not enabled")
    }

    private fun validatePoint(x: Float, y: Float): SonicLinkControlResult? {
        val display = SonicLinkDeviceInfo.displayInfo(this)
        val inBounds = x >= 0 && y >= 0 && x <= display.width && y <= display.height
        return if (inBounds) {
            null
        } else {
            SonicLinkControlResult.failure(
                "coordinate_out_of_bounds",
                "point ($x,$y) is outside ${display.width}x${display.height}"
            )
        }
    }

    private fun respond(requestId: String?, result: SonicLinkControlResult) {
        sendEnvelope("command_response", requestId, result)
    }

    private fun sendError(requestId: String?, code: String, message: String) {
        respond(requestId, SonicLinkControlResult.failure(code, message))
    }

    private fun sendEnvelope(type: String, requestId: String? = null, payload: Any? = null) {
        val payloadElement: JsonElement? = payload?.let { gson.toJsonTree(it) }
        val envelope = SonicLinkEnvelope(
            type = type,
            requestId = requestId,
            deviceId = deviceId,
            payload = payloadElement
        )
        webSocket?.send(gson.toJson(envelope))
    }

    private fun stopAgent(stopService: Boolean = true) {
        if (isStopping) {
            return
        }
        isStopping = true
        shouldRun = false
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        screenStreamer.stop()
        updateForegroundServiceType(includeMediaProjection = false)
        webSocket?.close(1000, "agent stopped")
        webSocket = null
        SonicLinkStatus.serviceRunning = false
        SonicLinkStatus.connectionState = SonicLinkConnectionState.STOPPED
        notifyStatusChanged()
        stopForeground(true)
        if (stopService) {
            stopSelf()
        }
        isStopping = false
    }

    private fun startAsForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateForegroundServiceType(includeMediaProjection: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val type = if (includeMediaProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SonicLinkAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.logo)
            .setContentTitle(getString(R.string.sonic_link_agent_title))
            .setContentText(notificationText())
            .setContentIntent(openPendingIntent)
            .setOngoing(SonicLinkStatus.connectionState != SonicLinkConnectionState.STOPPED)
            .addAction(R.mipmap.logo, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun notificationText(): String {
        return when (SonicLinkStatus.connectionState) {
            SonicLinkConnectionState.CONNECTED -> getString(R.string.sonic_link_status_connected)
            SonicLinkConnectionState.CONNECTING -> getString(R.string.sonic_link_status_connecting)
            SonicLinkConnectionState.RECONNECTING -> getString(R.string.sonic_link_status_reconnecting)
            SonicLinkConnectionState.ERROR -> SonicLinkStatus.lastError ?: getString(R.string.sonic_link_status_error)
            SonicLinkConnectionState.DISCONNECTED -> getString(R.string.sonic_link_status_disconnected)
            SonicLinkConnectionState.STOPPED -> getString(R.string.sonic_link_status_stopped)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sonic_link_agent_title),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notifyStatusChanged() {
        val intent = Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun JsonObject.string(name: String): String {
        return get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }

    private fun JsonObject.float(name: String): Float {
        return get(name)?.takeIf { !it.isJsonNull }?.asFloat ?: 0f
    }

    private fun JsonObject.long(name: String, defaultValue: Long): Long {
        return get(name)?.takeIf { !it.isJsonNull }?.asLong ?: defaultValue
    }

    private fun JsonObject.int(name: String, defaultValue: Int): Int {
        return get(name)?.takeIf { !it.isJsonNull }?.asInt ?: defaultValue
    }

    companion object {
        const val ACTION_STATUS_CHANGED = "org.cloud.sonic.android.agent.STATUS_CHANGED"
        private const val ACTION_STOP = "org.cloud.sonic.android.agent.STOP"
        private const val CHANNEL_ID = "sonic_link_agent"
        private const val NOTIFICATION_ID = 2024
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_EXPONENT = 5

        fun start(context: Context) {
            val intent = Intent(context, SonicLinkAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SonicLinkAgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
