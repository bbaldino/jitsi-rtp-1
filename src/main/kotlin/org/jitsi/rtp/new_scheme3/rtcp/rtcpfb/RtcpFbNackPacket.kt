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

package org.jitsi.rtp.new_scheme3.rtcp.rtcpfb

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.new_scheme3.rtcp.RtcpHeader
import org.jitsi.rtp.new_scheme3.rtcp.rtcpfb.fci.GenericNack
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 */
//TODO: can we have multiple NACK FCIs?
class RtcpFbNackPacket(
    header: RtcpHeader = RtcpHeader(),
    mediaSourceSsrc: Long = -1,
    private val fci: GenericNack = GenericNack(),
    backingBuffer: ByteBuffer? = null
) : TransportLayerFbPacket(header.modify { reportCount = FMT }, mediaSourceSsrc, fci, backingBuffer) {

    val missingSeqNums: List<Int> get() = fci.missingSeqNums

    constructor(mediaSourceSsrc: Long, missingSeqNums: List<Int>) : this(mediaSourceSsrc = mediaSourceSsrc)

    override fun clone(): RtcpFbNackPacket {
        return RtcpFbNackPacket(cloneMutableHeader(), mediaSourceSsrc, fci.clone())
    }

    companion object {
        const val FMT = 1
        const val SIZE_BYTES = RtcpFbPacket.FIXED_HEADER_SIZE + 4
        fun fromBuffer(buf: ByteBuffer): RtcpFbNackPacket {
            val bufStartPosition = buf.position()
            val header = RtcpHeader.create(buf)
            val mediaSourceSsrc = RtcpFbPacket.getMediaSourceSsrc(buf)
            val fci = GenericNack.fromBuffer(buf)

            return RtcpFbNackPacket(header, mediaSourceSsrc, fci, buf.subBuffer(bufStartPosition, buf.position()))
        }
        fun fromValues(
            header: RtcpHeader = RtcpHeader(),
            mediaSourceSsrc: Long = -1,
            missingSeqNums: List<Int> = listOf()
        ): RtcpFbNackPacket {
            val fci = GenericNack.fromValues(missingSeqNums)
            return RtcpFbNackPacket(header, mediaSourceSsrc, fci)
        }
    }
}
