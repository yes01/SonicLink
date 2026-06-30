package org.cloud.sonic.android.agent

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.cloud.sonic.android.ScreenCaptureState
import org.cloud.sonic.android.accessibility.SonicLinkControlResult
import org.cloud.sonic.android.utils.SLog
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class SonicLinkScreenStreamer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webSocketProvider: () -> WebSocket?,
    private val streamEventSender: (String, Any) -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var streamJob: Job? = null
    private var rotationWatchJob: Job? = null
    private var codecConfigPayload: ByteArray? = null
    private var isStopping = false
    private var sendFailureNotified = false
    private var config = StreamConfig()
    private var requestedConfig = StreamConfig()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handleProjectionStoppedBySystem()
        }
    }

    val isStreaming: Boolean
        get() = streamJob?.isActive == true

    fun start(config: StreamConfig = StreamConfig()): SonicLinkControlResult {
        if (!ScreenCaptureState.hasPermission) {
            return SonicLinkControlResult.failure(
                "screen_permission_missing",
                "screen capture permission is missing or has already been used; grant screen capture again on the phone"
            )
        }
        if (isStreaming) {
            return SonicLinkControlResult.success("stream already running")
        }
        requestedConfig = config
        this.config = config.normalized(context)
        sendFailureNotified = false
        val data = ScreenCaptureState.data
            ?: return SonicLinkControlResult.failure(
                "screen_permission_missing",
                "screen capture permission data is missing; grant screen capture again on the phone"
            )
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(ScreenCaptureState.resultCode, data)
        ScreenCaptureState.markConsumed()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjection?.registerCallback(projectionCallback, null)
        }

        return try {
            prepareEncoder()
            createVirtualDisplay()
            SonicLinkStatus.screenStreaming = true
            SonicLinkStatus.lastStreamEvent = "stream_started"
            streamJob = scope.launch(Dispatchers.IO) {
                drainEncoder()
            }
            startRotationWatcher()
            SonicLinkControlResult.success("stream started")
        } catch (e: Exception) {
            SLog.e("Failed to start screen stream", e)
            stop()
            SonicLinkControlResult.failure("stream_start_failed", e.message ?: "failed to start stream")
        }
    }

    fun stop(): SonicLinkControlResult {
        if (isStopping) {
            return SonicLinkControlResult.success("stream stopped")
        }
        isStopping = true
        releaseStream(cancelRotationWatcher = true, stopProjection = true)
        ScreenCaptureState.clear()
        SonicLinkStatus.screenStreaming = false
        SonicLinkStatus.lastStreamEvent = "stream_stopped"
        isStopping = false
        return SonicLinkControlResult.success("stream stopped; screen capture permission must be granted again before the next stream")
    }

    private fun handleProjectionStoppedBySystem() {
        if (isStopping) {
            return
        }
        releaseStream(cancelRotationWatcher = true, stopProjection = false)
        ScreenCaptureState.clear()
        SonicLinkStatus.screenStreaming = false
        SonicLinkStatus.screenCaptureRevokedAt = System.currentTimeMillis()
        SonicLinkStatus.lastStreamEvent = "screen_capture_revoked"
        streamEventSender(
            "stream_stopped",
            mapOf(
                "reason" to "screen_capture_revoked",
                "message" to "Screen capture permission was revoked by the system"
            )
        )
    }

    private fun restartForDisplayChange() {
        if (!isStreaming) {
            return
        }
        val previous = config
        val restartConfig = requestedConfig.normalized(context)
        if (previous.width == restartConfig.width &&
            previous.height == restartConfig.height &&
            previous.rotation == restartConfig.rotation
        ) {
            return
        }
        SonicLinkStatus.lastStreamEvent = "stream_stopped"
        streamEventSender(
            "stream_stopped",
            mapOf(
                "reason" to "display_changed",
                "message" to "Display changed; grant screen capture again before restarting the stream"
            )
        )
        scope.launch(Dispatchers.IO) {
            stop()
        }
    }

    private fun releaseStream(cancelRotationWatcher: Boolean, stopProjection: Boolean) {
        streamJob?.cancel()
        if (cancelRotationWatcher) {
            rotationWatchJob?.cancel()
            rotationWatchJob = null
        }
        streamJob = null
        releaseVideoPipeline()
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        if (stopProjection) {
            runCatching { mediaProjection?.stop() }
        }
        mediaProjection = null
    }

    private fun releaseVideoPipeline() {
        runCatching { mediaCodec?.signalEndOfInputStream() }
        runCatching { virtualDisplay?.release() }
        runCatching { inputSurface?.release() }
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        virtualDisplay = null
        inputSurface = null
        mediaCodec = null
        codecConfigPayload = null
    }

    private fun startRotationWatcher() {
        rotationWatchJob?.cancel()
        rotationWatchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(DISPLAY_WATCH_INTERVAL_MS)
                restartForDisplayChange()
            }
        }
    }

    private fun prepareEncoder() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
        }
        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec
    }

    private fun createVirtualDisplay() {
        val projection = mediaProjection ?: error("media projection is not ready")
        val surface = inputSurface ?: error("encoder surface is not ready")
        virtualDisplay = projection.createVirtualDisplay(
            "SonicLinkScreen",
            config.width,
            config.height,
            config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    private suspend fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (currentCoroutineContext().isActive) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outputBufferId >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        sendPacket(outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sendCodecConfig(format)
                }
            }
        }
    }

    private fun sendPacket(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        val payload = ByteArray(bufferInfo.size)
        outputBuffer.get(payload)
        val type = if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            SonicLinkVideoPacket.TYPE_CODEC_CONFIG
        } else if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            SonicLinkVideoPacket.TYPE_KEY_FRAME
        } else {
            SonicLinkVideoPacket.TYPE_FRAME
        }
        if (type == SonicLinkVideoPacket.TYPE_FRAME && isWebSocketBackedUp()) {
            return
        }
        if (type == SonicLinkVideoPacket.TYPE_KEY_FRAME) {
            if (isWebSocketBackedUp()) {
                return
            }
            codecConfigPayload?.let {
                sendBinary(SonicLinkVideoPacket.TYPE_CODEC_CONFIG, bufferInfo.presentationTimeUs / 1000L, it)
            }
        }
        sendBinary(type, bufferInfo.presentationTimeUs / 1000L, payload)
    }

    private fun sendCodecConfig(format: MediaFormat) {
        val configBytes = mutableListOf<Byte>()
        appendFormatBuffer(format, "csd-0", configBytes)
        appendFormatBuffer(format, "csd-1", configBytes)
        if (configBytes.isNotEmpty()) {
            val payload = configBytes.toByteArray()
            codecConfigPayload = payload
            sendBinary(SonicLinkVideoPacket.TYPE_CODEC_CONFIG, System.currentTimeMillis(), payload)
        }
    }

    private fun appendFormatBuffer(format: MediaFormat, key: String, output: MutableList<Byte>) {
        if (!format.containsKey(key)) {
            return
        }
        val buffer = format.getByteBuffer(key)?.duplicate() ?: return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        output.addAll(bytes.toList())
    }

    private fun sendBinary(type: Byte, timestampMs: Long, payload: ByteArray) {
        val socket = webSocketProvider()
        if (socket == null) {
            notifySendFailure("agent websocket is not connected")
            return
        }
        if (socket.queueSize() > MAX_WEBSOCKET_QUEUE_BYTES) {
            return
        }
        val packet = ByteBuffer.allocate(SonicLinkVideoPacket.HEADER_SIZE + payload.size)
            .put(type)
            .putLong(timestampMs)
            .putInt(config.width)
            .putInt(config.height)
            .putInt(config.rotation)
            .putInt(payload.size)
            .put(payload)
            .array()
        if (!socket.send(packet.toByteString())) {
            SLog.w("Drop screen stream packet because agent websocket queue is closed or full")
        }
    }

    private fun isWebSocketBackedUp(): Boolean {
        return (webSocketProvider()?.queueSize() ?: 0L) > MAX_WEBSOCKET_QUEUE_BYTES
    }

    private fun notifySendFailure(message: String) {
        if (sendFailureNotified || isStopping) {
            return
        }
        sendFailureNotified = true
        SLog.e("Screen stream send failed: $message")
        SonicLinkStatus.lastStreamEvent = "stream_error"
        streamEventSender(
            "stream_error",
            mapOf(
                "reason" to "websocket_send_failed",
                "message" to message
            )
        )
        scope.launch(Dispatchers.IO) {
            stop()
        }
    }

    data class StreamConfig(
        val width: Int = 0,
        val height: Int = 0,
        val bitRate: Int = 600_000,
        val frameRate: Int = 8,
        val iFrameIntervalSeconds: Int = 1,
        val rotation: Int = 0,
        val densityDpi: Int = 0
    ) {
        fun normalized(context: Context): StreamConfig {
            val display = SonicLinkDeviceInfo.displayInfo(context)
            val sourceWidth = if (width > 0) width else display.width
            val sourceHeight = if (height > 0) height else display.height
            val scale = (MAX_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight)).coerceAtMost(1f)
            val normalizedWidth = even((sourceWidth * scale).roundToInt())
            val normalizedHeight = even((sourceHeight * scale).roundToInt())
            return copy(
                width = normalizedWidth.coerceAtLeast(2),
                height = normalizedHeight.coerceAtLeast(2),
                bitRate = bitRate.coerceIn(300_000, 8_000_000),
                frameRate = frameRate.coerceIn(5, 30),
                iFrameIntervalSeconds = iFrameIntervalSeconds.coerceIn(1, 10),
                rotation = display.rotation,
                densityDpi = if (densityDpi > 0) densityDpi else display.densityDpi
            )
        }

        private fun even(value: Int): Int {
            return if (value % 2 == 0) value else value - 1
        }
    }

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val MAX_EDGE = 720
        private const val DISPLAY_WATCH_INTERVAL_MS = 1_000L
        private const val MAX_WEBSOCKET_QUEUE_BYTES = 512L * 1024L
    }
}
