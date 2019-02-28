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

package org.jitsi.rtp.new_scheme2.rtp

import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpHeaderExtensions
import org.jitsi.rtp.Serializable
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.new_scheme2.ConstructableFromBuffer
import org.jitsi.rtp.new_scheme2.ImmutableSerializableData
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

/**
 *
 * https://tools.ietf.org/html/rfc3550#section-5.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
private class RtpHeaderData(
    var version: Int = 2,
    var hasPadding: Boolean = false,
    var marker: Boolean = false,
    var payloadType: Int = 0,
    var sequenceNumber: Int = 0,
    var timestamp: Long = 0,
    var ssrc: Long = 0,
    var csrcs: MutableList<Long> = mutableListOf(),
    var extensions: RtpHeaderExtensions = RtpHeaderExtensions.NO_EXTENSIONS
) : Serializable {
    val sizeBytes: Int
        get() = RtpHeader.FIXED_SIZE_BYTES +
                (csrcCount * RtpHeader.CSRC_SIZE_BYTES) +
                extensions.size

    val hasExtension: Boolean
        get() = extensions.isNotEmpty()

    val csrcCount
        get() = csrcs.size

    fun clone(): RtpHeaderData {
        return RtpHeaderData(
            version,
            hasPadding,
            marker,
            payloadType,
            sequenceNumber,
            timestamp,
            ssrc,
            csrcs.toMutableList(),
            //TODO(brian): add clone method to extensions
            extensions
        )
    }

    override fun toString(): String = with (StringBuffer()) {
        appendln("size: $sizeBytes")
        appendln("version: $version")
        appendln("hasPadding: $hasPadding")
        appendln("hasExtension: $hasExtension")
        appendln("csrcCount: $csrcCount")
        appendln("marker: $marker")
        appendln("payloadType: $payloadType")
        appendln("sequenceNumber: $sequenceNumber")
        appendln("timestamp: $timestamp")
        appendln("ssrc: $ssrc")
        appendln("csrcs: $csrcs")
        appendln("Extensions: $extensions")
        toString()
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBuffer.allocate(sizeBytes)
        serializeTo(b)
        return b.rewind() as ByteBuffer
    }

    //TODO(brian): we assume that the buf's position 0 is the start
    // of the header, as the methods here use absolute positioning (which
    // makes sense for certain scenarios, but doesn't work as well for
    // serializing to an existing buffer which may have other stuff
    // before it
    override fun serializeTo(buf: ByteBuffer) {
        RtpHeader.setVersion(buf, version)
        RtpHeader.setPadding(buf, hasPadding)
        RtpHeader.setExtension(buf, hasExtension)
        RtpHeader.setCsrcCount(buf, csrcCount)
        RtpHeader.setMarker(buf, marker)
        RtpHeader.setPayloadType(buf, payloadType)
        RtpHeader.setSequenceNumber(buf, sequenceNumber)
        RtpHeader.setTimestamp(buf, timestamp)
        RtpHeader.setSsrc(buf, ssrc)
        RtpHeader.setCsrcs(buf, csrcs)
        if (hasExtension) {
            // Write the generic extension header (the cookie and the length)
            buf.position(RtpHeader.getExtensionsHeaderOffset(csrcCount))
            RtpHeader.setExtensions(buf, extensions)
        }
    }

    companion object : ConstructableFromBuffer<RtpHeaderData> {
        override fun fromBuffer(buf: ByteBuffer): RtpHeaderData {
            val version = RtpHeader.getVersion(buf)
            val hasPadding = RtpHeader.hasPadding(buf)
            val hasExtension = RtpHeader.getExtension(buf)
            val csrcCount = RtpHeader.getCsrcCount(buf)
            val marker = RtpHeader.getMarker(buf)
            val payloadType = RtpHeader.getPayloadType(buf)
            val sequenceNumber = RtpHeader.getSequenceNumber(buf)
            val timestamp = RtpHeader.getTimestamp(buf)
            val ssrc = RtpHeader.getSsrc(buf)
            val csrcs = RtpHeader.getCsrcs(buf, csrcCount)

            val extensions = if (hasExtension) {
                RtpHeader.getExtensions(buf.subBuffer(RtpHeader.getExtensionsHeaderOffset(csrcCount)))
            } else {
                RtpHeaderExtensions.NO_EXTENSIONS
            }
            return RtpHeaderData(
                version,
                hasPadding,
                marker,
                payloadType,
                sequenceNumber,
                timestamp,
                ssrc,
                csrcs,
                extensions
            )
        }
    }
}

class ImmutableRtpHeader private constructor(
    private val headerData: RtpHeaderData = RtpHeaderData(),
    backingBuffer: ByteBuffer? = null
) : ImmutableSerializableData() {

    constructor(
        version: Int = 2,
        hasPadding: Boolean = false,
        marker: Boolean = false,
        payloadType: Int = 0,
        sequenceNumber: Int = 0,
        timestamp: Long = 0,
        ssrc: Long = 0,
        csrcs: MutableList<Long> = mutableListOf(),
        extensions: RtpHeaderExtensions = RtpHeaderExtensions.NO_EXTENSIONS,
        backingBuffer: ByteBuffer? = null
    ) : this(RtpHeaderData(
            version, hasPadding, marker, payloadType, sequenceNumber,
            timestamp, ssrc, csrcs, extensions), backingBuffer)

    override val dataBuf: ByteBuffer by lazy {
        val b = ByteBufferUtils.ensureCapacity(backingBuffer, headerData.sizeBytes)
        b.rewind()
        b.limit(headerData.sizeBytes)
        headerData.serializeTo(b)
        b.rewind() as ByteBuffer
    }

    val version: Int = headerData.version
    val hasPadding: Boolean = headerData.hasPadding
    val marker: Boolean = headerData.marker
    val payloadType: Int = headerData.payloadType
    val sequenceNumber: Int = headerData.sequenceNumber
    val timestamp: Long = headerData.timestamp
    val ssrc: Long = headerData.ssrc
    val csrcs: List<Long> = headerData.csrcs
    //TODO(brian): need a readonly RtpheaderExtensions
    val extensions: RtpHeaderExtensions = headerData.extensions

    val sizeBytes: Int = headerData.sizeBytes

    companion object : ConstructableFromBuffer<ImmutableRtpHeader> {
        override fun fromBuffer(buf: ByteBuffer): ImmutableRtpHeader {
            val rtpHeaderData = RtpHeaderData.fromBuffer(buf)
            return ImmutableRtpHeader(rtpHeaderData, buf)
        }
    }
}
