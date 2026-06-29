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
    private val webSocketProvider: () -> WebSocket?
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var streamJob: Job? = null
    private var isStopping = false
    private var config = StreamConfig()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }
    }

    val isStreaming: Boolean
        get() = streamJob?.isActive == true

    fun start(config: StreamConfig = StreamConfig()): SonicLinkControlResult {
        if (!ScreenCaptureState.hasPermission) {
            return SonicLinkControlResult.failure("screen_permission_missing", "screen capture permission is not granted")
        }
        if (isStreaming) {
            return SonicLinkControlResult.success("stream already running")
        }
        this.config = config.normalized(context)
        val data = ScreenCaptureState.data
            ?: return SonicLinkControlResult.failure("screen_permission_missing", "screen capture permission data is missing")
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(ScreenCaptureState.resultCode, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjection?.registerCallback(projectionCallback, null)
        }

        return try {
            prepareEncoder()
            createVirtualDisplay()
            SonicLinkStatus.screenStreaming = true
            streamJob = scope.launch(Dispatchers.IO) {
                drainEncoder()
            }
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
        streamJob?.cancel()
        streamJob = null
        runCatching { mediaCodec?.signalEndOfInputStream() }
        runCatching { virtualDisplay?.release() }
        runCatching { inputSurface?.release() }
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        inputSurface = null
        mediaCodec = null
        mediaProjection = null
        SonicLinkStatus.screenStreaming = false
        isStopping = false
        return SonicLinkControlResult.success("stream stopped")
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
        sendBinary(type, bufferInfo.presentationTimeUs / 1000L, payload)
    }

    private fun sendCodecConfig(format: MediaFormat) {
        val configBytes = mutableListOf<Byte>()
        appendFormatBuffer(format, "csd-0", configBytes)
        appendFormatBuffer(format, "csd-1", configBytes)
        if (configBytes.isNotEmpty()) {
            sendBinary(SonicLinkVideoPacket.TYPE_CODEC_CONFIG, System.currentTimeMillis(), configBytes.toByteArray())
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
        val packet = ByteBuffer.allocate(SonicLinkVideoPacket.HEADER_SIZE + payload.size)
            .put(type)
            .putLong(timestampMs)
            .putInt(config.width)
            .putInt(config.height)
            .putInt(config.rotation)
            .putInt(payload.size)
            .put(payload)
            .array()
        webSocketProvider()?.send(packet.toByteString())
    }

    data class StreamConfig(
        val width: Int = 0,
        val height: Int = 0,
        val bitRate: Int = 2_000_000,
        val frameRate: Int = 20,
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
                bitRate = bitRate.coerceIn(500_000, 8_000_000),
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
        private const val MAX_EDGE = 1280
    }
}
