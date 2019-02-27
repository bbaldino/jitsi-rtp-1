/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.rtp.new_scheme3.rtp

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

/**
 * To get the original [RtpPacket] from this [RtxPacket], just use it
 * as the [RtpPacket] instance
 */
class RtxPacket internal constructor(
    header: RtpHeader = RtpHeader(),
    payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    val originalSequenceNumber: Int = 0,
    backingBuffer: ByteBuffer? = null
) : RtpPacket(header, payload, backingBuffer) {

    companion object {
        // Create an RTX packet (to be sent out) from a previously-sent
        // RTP packet
        fun fromRtpPacket(rtpPacket: RtpPacket): RtxPacket {
            return rtpPacket.toOtherRtpPacketType { rtpHeader, payload, backingBuffer ->
                RtxPacket(rtpHeader, payload, rtpPacket.header.sequenceNumber, backingBuffer)
            }
        }
        // Convert a packet, currently parsed as an RTP packet, to an RTX packet
        // (i.e., an incoming packet, previously parsed as an RTP packet, has been
        // determined to actually be an RTX packet, so re-parse it as such
        fun parseAsRtx(rtpPacket: RtpPacket): RtxPacket {
            return rtpPacket.toOtherRtpPacketType { rtpHeader, payload, backingBuffer ->
                val originalSequenceNumber = payload.getShort().toPositiveInt()
                val actualPayload = payload.subBuffer(payload.position())
                RtxPacket(rtpHeader, actualPayload, originalSequenceNumber, backingBuffer )
            }
        }
        // Parse a buffer as an RTX packet
        fun fromBuffer(buf: ByteBuffer): RtxPacket {
            val header = RtpHeader.create(buf)
            val originalSequenceNumber = buf.getShort(header.sizeBytes).toInt()
            val payload = buf.subBuffer(header.sizeBytes + 2)

            return RtxPacket(header, payload, originalSequenceNumber, buf)
        }
    }
}