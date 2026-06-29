package org.cloud.sonic.android.agent

object SonicLinkVideoPacket {
    const val TYPE_CODEC_CONFIG: Byte = 1
    const val TYPE_KEY_FRAME: Byte = 2
    const val TYPE_FRAME: Byte = 3

    const val HEADER_SIZE = 1 + 8 + 4 + 4 + 4 + 4
}
