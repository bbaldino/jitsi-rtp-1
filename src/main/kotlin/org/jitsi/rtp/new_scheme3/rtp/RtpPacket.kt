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

import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.new_scheme3.Packet
import org.jitsi.rtp.new_scheme3.rtp.data.RtpHeaderData
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

open class RtpPacket(
    protected val _header: RtpHeader = RtpHeader(),
    protected val _payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    private var backingBuffer: ByteBuffer? = null
) : Packet() {
    val payload: ByteBuffer get() = _payload.asReadOnlyBuffer()
    val header: ImmutableRtpHeader get() = _header

    override val sizeBytes: Int
        get() = _header.sizeBytes + _payload.limit()

    private var dirty: Boolean = true

    fun modifyHeader(block: RtpHeaderData.() -> Unit) {
        _header.modify(block)
    }

    fun modifyPayload(block: ByteBuffer.() -> Unit) {
        with (_payload) {
            block()
        }
    }

    override fun clone(): Packet {
        return RtpPacket(_header.clone(), _payload.clone())
    }

    override fun getBuffer(): ByteBuffer {
        if (dirty) {
            //TODO: we should somehow track the original limit we were given for
            // this buffer, so that we can do things like re-add the auth tag
            // into the available space
            val buf = ByteBufferUtils.ensureCapacity(backingBuffer, sizeBytes)
            buf.put(header.getBuffer())
            _payload.rewind()
            buf.put(_payload)

            buf.rewind()
            backingBuffer = buf
            dirty = false
        }
        return backingBuffer!!
    }
}