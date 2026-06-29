package org.cloud.sonic.android.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class SonicLinkVideoPacketTest {
    @Test
    fun headerSizeMatchesBinaryProtocol() {
        assertEquals(25, SonicLinkVideoPacket.HEADER_SIZE)
    }

    @Test
    fun binaryPacketHeaderCanBeDecodedByPlatform() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = ByteBuffer.allocate(SonicLinkVideoPacket.HEADER_SIZE + payload.size)
            .put(SonicLinkVideoPacket.TYPE_KEY_FRAME)
            .putLong(1234L)
            .putInt(720)
            .putInt(1280)
            .putInt(1)
            .putInt(payload.size)
            .put(payload)
            .array()

        val buffer = ByteBuffer.wrap(packet)
        assertEquals(SonicLinkVideoPacket.TYPE_KEY_FRAME, buffer.get())
        assertEquals(1234L, buffer.long)
        assertEquals(720, buffer.int)
        assertEquals(1280, buffer.int)
        assertEquals(1, buffer.int)
        assertEquals(payload.size, buffer.int)
    }
}
