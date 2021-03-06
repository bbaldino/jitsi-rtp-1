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
package org.jitsi.rtp

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.ByteBufferUtils
import toUInt
import unsigned.toUInt
import unsigned.toULong
import unsigned.toUShort
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
open class RtpHeader : Serializable {
    private var buf: ByteBuffer? = null
    var version: Int
    var hasPadding: Boolean
    var hasExtension: Boolean
    var csrcCount: Int
    var marker: Boolean
    var payloadType: Int
    var sequenceNumber: Int
    var timestamp: Long
    var ssrc: Long
    var csrcs: MutableList<Long>
    var extensions: RtpHeaderExtensions
    val size: Int
        get() = RtpHeader.FIXED_SIZE_BYTES +
                (csrcCount * RtpHeader.CSRC_SIZE_BYTES) +
                extensions.size

    /**
     * The offset at which the generic extension header should be placed
     */
    private fun getExtensionsHeaderOffset(): Int = RtpHeader.FIXED_SIZE_BYTES + (csrcCount * RtpHeader.CSRC_SIZE_BYTES)
    /**
     * Gives the offset into the buffer the extensions themselves should appear at.  NOTE that this is AFTER
     * the extension header
     */
    private fun getExtensionsOffset(): Int = getExtensionsHeaderOffset() + RtpHeaderExtensions.EXTENSIONS_HEADER_SIZE

    companion object {
        const val FIXED_SIZE_BYTES = 12
        const val CSRC_SIZE_BYTES = 4

        fun getVersion(buf: ByteBuffer): Int = buf.get(0).getBits(0, 2).toUInt()
        fun setVersion(buf: ByteBuffer, version: Int) = buf.putBits(0, 0, version.toByte(), 2)

        fun hasPadding(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(2)
        fun setPadding(buf: ByteBuffer, hasPadding: Boolean) = buf.putBitAsBoolean(0, 3, hasPadding)

        fun getExtension(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(3)
        fun setExtension(buf: ByteBuffer, hasExtension: Boolean) = buf.putBitAsBoolean(0, 3, hasExtension)

        fun getCsrcCount(buf: ByteBuffer): Int = buf.get(0).getBits(4, 4).toUInt()
        fun setCsrcCount(buf: ByteBuffer, csrcCount: Int) {
            buf.putBits(0, 4, csrcCount.toByte(), 4)
        }

        fun getMarker(buf: ByteBuffer): Boolean = buf.get(1).getBitAsBool(0)
        fun setMarker(buf: ByteBuffer, isSet: Boolean) {
            buf.putBitAsBoolean(1, 0, isSet)
        }

        fun getPayloadType(buf: ByteBuffer): Int = buf.get(1).getBits(1, 7).toUInt()
        fun setPayloadType(buf: ByteBuffer, payloadType: Int) {
            buf.putBits(1, 1, payloadType.toByte(), 7)
        }

        fun getSequenceNumber(buf: ByteBuffer): Int = buf.getShort(2).toUInt()
        fun setSequenceNumber(buf: ByteBuffer, sequenceNumber: Int) {
            buf.putShort(2, sequenceNumber.toUShort())
        }

        fun getTimestamp(buf: ByteBuffer): Long = buf.getInt(4).toULong()
        fun setTimestamp(buf: ByteBuffer, timestamp: Long) {
            buf.putInt(4, timestamp.toUInt())
        }

        fun getSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setSsrc(buf: ByteBuffer, ssrc: Long) {
            buf.putInt(8, ssrc.toUInt())
        }

        fun getCsrcs(buf: ByteBuffer, csrcCount: Int): MutableList<Long> {
            return (0 until csrcCount).map {
                buf.getInt(12 + (it * RtpHeader.CSRC_SIZE_BYTES)).toULong()
            }.toMutableList()
        }
        fun setCsrcs(buf: ByteBuffer, csrcs: List<Long>) {
            csrcs.forEachIndexed { index, csrc ->
                buf.putInt(12 + (index * RtpHeader.CSRC_SIZE_BYTES), csrc.toUInt())
            }
        }

        /**
         * Note that the buffer passed to these two methods, unlike in most other helpers, must already
         * begin at the start of the extensions portion of the header.  This method also
         * assumes that the caller has already verified that there *are* extensions present
         * (i.e. the extension bit is set) in the case of 'getExtensions' or that there is space
         * for the extensions in the passed buffer (in the case of 'setExtensionsAndPadding')
         */
        /**
         * The buffer passed here should point to the start of the generic extension header
         */
        fun getExtensions(extensionsBuf: ByteBuffer): RtpHeaderExtensions = RtpHeaderExtensions(extensionsBuf)

        /**
         * [buf] should point to the start of the generic extension header
         */
        fun setExtensions(buf: ByteBuffer, extensions: RtpHeaderExtensions) {
            buf.put(extensions.getBuffer())
        }
    }

    constructor(buf: ByteBuffer) {
        this.version = RtpHeader.getVersion(buf)
        this.hasPadding = RtpHeader.hasPadding(buf)
        this.hasExtension = RtpHeader.getExtension(buf)
        this.csrcCount = RtpHeader.getCsrcCount(buf)
        this.marker = RtpHeader.getMarker(buf)
        this.payloadType = RtpHeader.getPayloadType(buf)
        this.sequenceNumber = RtpHeader.getSequenceNumber(buf)
        this.timestamp = RtpHeader.getTimestamp(buf)
        this.ssrc = RtpHeader.getSsrc(buf)
        this.csrcs = RtpHeader.getCsrcs(buf, this.csrcCount)

        extensions = if (hasExtension) RtpHeader.getExtensions(buf.subBuffer(getExtensionsHeaderOffset())) else RtpHeaderExtensions.NO_EXTENSIONS
        this.buf = buf.subBuffer(0, this.size)
    }

    constructor(
        version: Int = 2,
        hasPadding: Boolean = false,
        csrcCount: Int = 0,
        marker: Boolean = false,
        payloadType: Int = 0,
        sequenceNumber: Int = 0,
        timestamp: Long = 0,
        ssrc: Long = 0,
        csrcs: MutableList<Long> = mutableListOf(),
        extensions: RtpHeaderExtensions = RtpHeaderExtensions.NO_EXTENSIONS
    ) {
        this.version = version
        this.hasPadding = hasPadding
        this.hasExtension = extensions.isNotEmpty()
        this.csrcCount = csrcCount
        this.marker = marker
        this.payloadType = payloadType
        this.sequenceNumber = sequenceNumber
        this.timestamp = timestamp
        this.ssrc = ssrc
        this.csrcs = csrcs
        this.extensions = extensions
    }

    fun getExtension(id: Int): RtpHeaderExtension? = extensions.getExtension(id)

    fun addExtension(id: Int, ext: RtpHeaderExtension) = extensions.addExtension(id, ext)

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        RtpHeader.setVersion(b, version)
        RtpHeader.setPadding(b, hasPadding)
        hasExtension = extensions.isNotEmpty()
        RtpHeader.setExtension(b, hasExtension)
        RtpHeader.setCsrcCount(b, csrcCount)
        RtpHeader.setMarker(b, marker)
        RtpHeader.setPayloadType(b, payloadType)
        RtpHeader.setSequenceNumber(b, sequenceNumber)
        RtpHeader.setTimestamp(b, timestamp)
        RtpHeader.setSsrc(b, ssrc)
        RtpHeader.setCsrcs(b, csrcs)
        if (hasExtension) {
            // Write the generic extension header (the cookie and the length)
            b.position(getExtensionsHeaderOffset())
            setExtensions(b, extensions)
        }
        b.rewind()
        buf = b
        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("size: $size")
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
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as RtpHeader
        return (size == other.size &&
                version == other.version &&
                hasPadding == other.hasPadding &&
                hasExtension == other.hasExtension &&
                csrcCount == other.csrcCount &&
                marker == other.marker &&
                payloadType == other.payloadType &&
                sequenceNumber == other.sequenceNumber &&
                timestamp == other.timestamp &&
                ssrc == other.ssrc &&
                csrcs.equals(other.csrcs) &&
                extensions.equals(other.extensions))
    }

    override fun hashCode(): Int {
        return size.hashCode() + version.hashCode() + hasPadding.hashCode() +
                hasExtension.hashCode() + csrcCount.hashCode() + marker.hashCode() +
                payloadType.hashCode() + sequenceNumber.hashCode() + timestamp.hashCode() +
                ssrc.hashCode() + csrcs.hashCode() + extensions.hashCode()
    }
}
