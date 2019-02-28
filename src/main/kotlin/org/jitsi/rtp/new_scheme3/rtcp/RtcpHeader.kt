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
import org.jitsi.rtp.new_scheme3.SerializableData
import org.jitsi.rtp.new_scheme3.rtcp.data.RtcpHeaderData
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

abstract class ImmutableRtcpHeader internal constructor(
    protected val headerData: RtcpHeaderData = RtcpHeaderData(),
    protected var backingBuffer: ByteBuffer? = null
) : SerializableData(), Cloneable<ImmutableRtcpHeader> {
    override val sizeBytes: Int
        get() = headerData.sizeBytes

    val version: Int by ImmutableAlias(headerData::version)
    val hasPadding: Boolean by ImmutableAlias(headerData::hasPadding)
    val reportCount: Int by ImmutableAlias(headerData::reportCount)
    val packetType: Int by ImmutableAlias(headerData::packetType)
    val length: Int by ImmutableAlias(headerData::length)
    val senderSsrc: Long by ImmutableAlias(headerData::senderSsrc)

    private var dirty: Boolean = true

    constructor(
        version: Int = 2,
        hasPadding: Boolean = false,
        reportCount: Int = 0,
        packetType: Int = 0,
        length: Int = 0,
        senderSsrc: Long = 0,
        backingBuffer: ByteBuffer? = null
    ) : this(RtcpHeaderData(
        version, hasPadding, reportCount,
        packetType, length, senderSsrc), backingBuffer)

    protected fun doModify(block: RtcpHeaderData.() -> Unit) {
        with (headerData) {
            block()
        }
        dirty = true
    }

    override fun getBuffer(): ByteBuffer {
        if (dirty) {
            val b = ByteBufferUtils.ensureCapacity(backingBuffer, sizeBytes)
            headerData.serializeTo(b)
            dirty = false
            backingBuffer = b
        }
        return backingBuffer!!.asReadOnlyBuffer()
    }
}

class RtcpHeader(
    headerData: RtcpHeaderData = RtcpHeaderData(),
    backingBuffer: ByteBuffer? = null
) : ImmutableRtcpHeader(headerData, backingBuffer) {

    override fun clone(): RtcpHeader =
            RtcpHeader(headerData.clone())

    fun modify(block: RtcpHeaderData.() -> Unit) {
        doModify(block)
    }

    override fun serializeTo(buf: ByteBuffer) {
        headerData.serializeTo(buf)
    }

    companion object {
        fun create(buf: ByteBuffer): RtcpHeader {
            val headerData = RtcpHeaderData.create(buf)
            return RtcpHeader(headerData, buf)
        }
    }
}