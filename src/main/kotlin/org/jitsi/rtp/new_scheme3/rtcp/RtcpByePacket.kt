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

package org.jitsi.rtp.new_scheme3.rtcp

import org.jitsi.rtp.new_scheme3.Cloneable
import org.jitsi.rtp.new_scheme3.ImmutableAlias
import org.jitsi.rtp.new_scheme3.Packet
import org.jitsi.rtp.new_scheme3.SerializableData
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class RtcpByeData(
    var ssrcs: MutableList<Long> = mutableListOf(),
    var reason: String? = null
) : SerializableData(), Cloneable<RtcpByeData> {

    override val sizeBytes: Int
        get() {
            val dataSize = ssrcs.size * 4
            val reasonSize: Int = reason?.let {
                val fieldSize = it.toByteArray(StandardCharsets.US_ASCII).size
                var paddingSize = 0
                while (fieldSize + paddingSize % 4 != 0) {
                    paddingSize++
                }
                fieldSize + paddingSize
            } ?: 0

            return dataSize + reasonSize
        }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBuffer.allocate(sizeBytes)
        serializeTo(b)

        return b.rewind() as ByteBuffer
    }

    override fun serializeTo(buf: ByteBuffer) {
        ssrcs.stream()
                .map(Long::toInt)
                .forEach { buf.putInt(it) }
        reason?.let {
            val reasonBuf = ByteBuffer.wrap(it.toByteArray(StandardCharsets.US_ASCII))
            buf.put(reasonBuf.limit().toByte())
            buf.put(reasonBuf)
            while (buf.position() % 4 != 0) {
                buf.put(0x00)
            }
        }
    }

    override fun clone(): RtcpByeData =
        RtcpByeData(ssrcs.toMutableList(), reason?.plus(""))

    companion object {
        fun create(buf: ByteBuffer, remainingSsrcCount: Int, hasReason: Boolean): RtcpByeData {
            val ssrcs = (1..remainingSsrcCount)
                    .map { buf.int }
                    .map { it.toLong() }
                    .toMutableList()

            val reason = if (hasReason) {
                val reasonLength = buf.get().toInt()
                String(buf.array(), buf.position(), reasonLength)
            } else {
                null
            }
            return RtcpByeData(ssrcs, reason)
        }
    }
}

class RtcpByePacket internal constructor(
    header: RtcpHeader = RtcpHeader(),
    private val byeData: RtcpByeData = RtcpByeData(),
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header, backingBuffer) {

    // Can't use an ImmutableAlias here because we have to combine the value with the one
    // in the header
    val ssrcs: List<Long> get() = byeData.ssrcs + listOf(header.senderSsrc)

    val reason: String? by ImmutableAlias(byeData::reason)

    override val sizeBytes: Int get() = header.sizeBytes + byeData.sizeBytes

    constructor(
        header: RtcpHeader = RtcpHeader(),
        // Not including the one in the header
        ssrcs: MutableList<Long> = mutableListOf(),
        reason: String? = null,
        backingBuffer: ByteBuffer? = null
    ) : this (header, RtcpByeData(ssrcs, reason), backingBuffer)

    override fun serializeTo(buf: ByteBuffer) {
        _header.serializeTo(buf)
        byeData.serializeTo(buf)
    }

    override fun clone(): Packet =
        RtcpByePacket(_header.clone(), byeData.clone())

    fun modify(block: RtcpByeData.() -> Unit) {
        with (byeData) {
            block()
            payloadModified()
        }
    }

    companion object {
        fun create(buf: ByteBuffer): RtcpByePacket {
            val header = RtcpHeader.create(buf)
            val hasReason = run {
                val packetLength = header.length
                val headerAndSsrcsLength = header.sizeBytes + (header.reportCount - 1) * 4
                headerAndSsrcsLength < packetLength
            }
            val byeData = RtcpByeData.create(buf, header.reportCount - 1, hasReason)
            return RtcpByePacket(header, byeData, buf)
        }
    }
}