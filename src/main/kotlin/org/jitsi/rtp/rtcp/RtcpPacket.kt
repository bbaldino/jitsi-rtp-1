/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import java.nio.ByteBuffer

abstract class RtcpPacket : Packet() {
    abstract var header: RtcpHeader
    /**
     * The size of this packet as it is represented by the RTCPFB length field
     * in the header:
     * "The length of this packet in 32-bit words minus one, including the
     * header and any padding.  This is in line with the definition of
     * the length field used in RTCP sender and receiver reports"
     */
    //TODO: it would be nice to put this in RtpProtocolPacket and have RtpPacket and
    // RtcpPacket inherit it?  or at least put it somewhere common for rtp
    protected val lengthValue: Int
        get() = ((size + 3) / 4 - 1)

    companion object {
        fun fromBuffer(buf: ByteBuffer): RtcpPacket {
            val packetType = RtcpHeader.getPacketType(buf)
            return when (packetType) {
                RtcpSrPacket.PT -> RtcpSrPacket(buf)
                RtcpRrPacket.PT -> RtcpRrPacket(buf)
                RtcpSdesPacket.PT -> RtcpSdesPacket(buf)
                RtcpByePacket.PT -> RtcpByePacket(buf)
                in RtcpFbPacket.PACKET_TYPES -> RtcpFbPacket.fromBuffer(buf)
                else -> throw Exception("Unsupported RTCP packet type $packetType")
            }
        }

        /**
         * [buf] should be a buffer whose start represents the start of the
         * RTCP packet (i.e. the start of the RTCP header)
         */
        fun setHeader(buf: ByteBuffer, header: RtcpHeader) {
            buf.put(header.getBuffer())
        }
    }

    val payload: ByteBuffer
        get() = getBuffer().subBuffer(header.size)

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCP packet")
            append(header.toString())
            toString()
        }
    }
}
