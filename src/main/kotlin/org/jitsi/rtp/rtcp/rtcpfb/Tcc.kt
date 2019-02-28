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
package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.extensions.get3Bytes
import org.jitsi.rtp.extensions.getBit
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.ByteBufferUtils
import org.jitsi.rtp.util.RtpUtils
import toUInt
import unsigned.toUByte
import unsigned.toUInt
import unsigned.toULong
import unsigned.toUShort
import java.nio.ByteBuffer
import java.util.*
import java.util.function.BiConsumer
import kotlin.experimental.and

/**
 * Map the tcc sequence number to the timestamp at which that packet
 * was received
 */
class PacketMap : TreeMap<Int, Long>(RtpUtils.rtpSeqNumComparator) {
    // Only does TwoBitPacketStatusSymbols since that's all we use
    fun getStatusSymbol(tccSeqNum: Int, referenceTime: Long): PacketStatusSymbol {
        val deltaMs = getDeltaMs(tccSeqNum, referenceTime) ?: return TwoBitPacketStatusSymbol.NOT_RECEIVED
        try {
            return TwoBitPacketStatusSymbol.fromDeltaMs(deltaMs)
        } catch (e: Exception) {
            println("Error getting status symbol: $e, current packet map: $this")
            throw e
        }
    }

    fun getDeltaMs(tccSeqNum: Int, referenceTime: Long): Double? {
        val timestamp = getOrDefault(tccSeqNum, NOT_RECEIVED_TS)
        if (timestamp == NOT_RECEIVED_TS) {
            return null
        }
        // Get the timestamp for the packet just before this one.  If there isn't one, then
        // this is the first packet so the delta is 0.0
        val previousTimestamp = getPreviousReceivedTimestamp(tccSeqNum)
        val deltaMs = when (previousTimestamp) {
            -1L -> timestamp - referenceTime
            else -> timestamp - previousTimestamp
        }
//        println("packet $tccSeqNum has delta $deltaMs (ts $timestamp, prev ts $previousTimestamp)")
        return deltaMs.toDouble()
    }

    /**
     * Get the timestamp of the packet before this one that was actually received (i.e. ignore
     * sequence numbers that weren't received)
     */
    private fun getPreviousReceivedTimestamp(tccSeqNum: Int): Long {
        var prevSeqNum = tccSeqNum - 1
        var previousTimestamp = NOT_RECEIVED_TS
        while (previousTimestamp == NOT_RECEIVED_TS && containsKey(prevSeqNum)) {
            previousTimestamp = floorEntry(prevSeqNum--)?.value ?: NOT_RECEIVED_TS
        }

        return previousTimestamp
    }
}

const val NOT_RECEIVED_TS: Long = -1

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|  FMT=15 |    PT=205     |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SSRC of packet sender                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      SSRC of media source                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      base sequence number     |      packet status count      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                 reference time                | fb pkt. count |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          packet chunk         |         packet chunk          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         packet chunk          |  recv delta   |  recv delta   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           recv delta          |  recv delta   | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class Tcc : FeedbackControlInformation {
    override var buf: ByteBuffer? = null
    override val fmt: Int = Tcc.FMT
    var feedbackPacketCount: Int
    private val packetInfo: PacketMap
    var referenceTimeMs: Long = -1
    override var size: Int = 0

    companion object {
        const val FMT = 15

        /**
         * [buf] should start at the beginning of the FCI block
         */
        fun getBaseSeqNum(buf: ByteBuffer): Int = buf.getShort(0).toUInt()

        fun setBaseSeqNum(buf: ByteBuffer, baseSeqNum: Int) {
            buf.putShort(0, baseSeqNum.toShort())
        }

        fun getPacketStatusCount(buf: ByteBuffer): Int = buf.getShort(2).toUInt()
        fun setPacketStatusCount(buf: ByteBuffer, packetStatusCount: Int) {
            buf.putShort(2, packetStatusCount.toUShort())
        }

        /**
         * The reference time field is stored as a time for some arbitrary clock and
         * as a multiple of 64ms.  The time returned here is the translated time
         * (i.e. not a multiple of 64ms but a timestamp in milliseconds directly)
         */
        fun getReferenceTimeMs(buf: ByteBuffer): Long = (buf.get3Bytes(4) shl 6).toULong()

        /**
         * [referenceTime] should be a standard timestamp in milliseconds, this method
         * will convert the value to the packet format (a value which is a multiple of 64ms).
         * [buf] should start at the beginning of the TCC FCI buffer
         */
        fun setReferenceTimeMs(buf: ByteBuffer, referenceTime: Long) {
            buf.put3Bytes(4, (referenceTime.toUInt() shr 6) and 0xFFFFFF)
        }

        fun getFeedbackPacketCount(buf: ByteBuffer): Int = buf.get(7).toUInt()
        fun setFeedbackPacketCount(buf: ByteBuffer, feedbackPacketCount: Int) {
            buf.put(7, feedbackPacketCount.toUByte())
        }

        /**
         * Given a buffer which starts at the beginning of the packet status chunks and the
         * packet status count, return a [Pair] containing 1) the list of [PacketStatusChunk]s
         * and 2) the list of [ReceiveDelta]s
         */
        private fun getPacketChunksAndDeltas(
            packetStatusBuf: ByteBuffer,
            packetStatusCount: Int
        ): Pair<List<PacketStatusSymbol>, List<ReceiveDelta>> {
            val packetStatuses = mutableListOf<PacketStatusSymbol>()
            var numPacketStatusProcessed = 0
            var currOffset = 0
            while (numPacketStatusProcessed < packetStatusCount) {
                val packetChunkBuf = packetStatusBuf.subBuffer(currOffset)
                val packetStatusChunk = PacketStatusChunk.parse(packetChunkBuf)
                packetStatusChunk.forEach { packetStatus ->
                    if (numPacketStatusProcessed >= packetStatusCount) {
                        return@forEach
                    }
                    packetStatuses.add(packetStatus)
                    numPacketStatusProcessed++
                }
                currOffset += PacketStatusChunk.SIZE_BYTES
            }

            val packetDeltas = mutableListOf<ReceiveDelta>()
            packetStatuses.forEach { packetStatus ->
                if (packetStatus.hasDelta()) {
                    val deltaSizeBytes = packetStatus.getDeltaSizeBytes()
                    val deltaBuf = packetStatusBuf.subBuffer(currOffset)
                    val receiveDelta = ReceiveDelta.parse(deltaBuf, deltaSizeBytes)
                    packetDeltas.add(receiveDelta)
                    currOffset += deltaSizeBytes
                }
            }

            return Pair(packetStatuses, packetDeltas)
        }

        /**
         * Given [buf], which is a buffer that starts at the beginning of the TCC FCI payload,
         * return a [PacketMap] containing the tcc sequence numbers and their received timestamps for
         * all packets described in this TCC packet
         */
        fun getPacketInfo(buf: ByteBuffer): PacketMap {
            val baseSeqNum = getBaseSeqNum(buf)
            val packetStatusCount = getPacketStatusCount(buf)
            val referenceTimeMs = getReferenceTimeMs(buf)

            val (packetStatuses, packetDeltas) =
                    getPacketChunksAndDeltas(buf.subBuffer(8), packetStatusCount)

            val packetInfo = PacketMap()
            val deltaIter = packetDeltas.iterator()
            var previousTimestamp = referenceTimeMs
            packetStatuses.forEachIndexed { index, packetStatus ->
                val seqNum = baseSeqNum + index
                val timestamp: Long = if (packetStatus.hasDelta()) {
                    val deltaMs = deltaIter.next().deltaMs.toLong()
                    val ts = previousTimestamp + deltaMs
                    previousTimestamp = ts
                    ts
                } else {
                    NOT_RECEIVED_TS
                }
                packetInfo[seqNum] = timestamp
            }
            return packetInfo
        }

        /**
         * Given [buf], which is a buffer that starts at the first packet status chunk, write all the
         * TCC packet information contained in [packetInfo] to the buffer.
         * For now we will always write status vector chunks with 2-bit symbols.
         */
        fun setPacketInfo(buf: ByteBuffer, packetInfo: PacketMap, referenceTimestamp: Long) {
            if (packetInfo.isEmpty()) {
                return
            }
            setBaseSeqNum(buf, packetInfo.firstKey())
            setPacketStatusCount(buf, packetInfo.size)
            setReferenceTimeMs(buf, referenceTimestamp)

            // Set the buffer's position to the start of the status chunks
            buf.position(8)

            val seqNums = packetInfo.keys.toList()
            val vectorChunks = mutableListOf<StatusVectorChunk>()
            val receiveDeltas = mutableListOf<ReceiveDelta>()
            // Each vector chunk will contain 7 packet status symbols
            for (vectorChunkStartIndex in 0 until packetInfo.size step 7) {
                val packetStatuses = mutableListOf<PacketStatusSymbol>()
                for (statusSymbolIndex in vectorChunkStartIndex until vectorChunkStartIndex + 7) {
                    if (statusSymbolIndex >= packetInfo.size) {
                        // The last block may not be full
                        break
                    }
                    val tccSeqNum = seqNums[statusSymbolIndex]
                    val symbol = packetInfo.getStatusSymbol(tccSeqNum, referenceTimestamp)
                    if (symbol.hasDelta()) {
                        val deltaMs = packetInfo.getDeltaMs(tccSeqNum, referenceTimestamp)!!
                        receiveDeltas.add(ReceiveDelta.create(deltaMs))
                    }
                    packetStatuses.add(symbol)
                }
                vectorChunks.add(StatusVectorChunk(2, packetStatuses))
            }
            vectorChunks.forEach {
                buf.put(it.getBuffer())
            }
            receiveDeltas.forEach {
                buf.put(it.getBuffer())
            }
        }
    }

    /**
     * Buf's position 0 must be the start of the FCI block and its limit
     * must represent the end (including
     */
    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.referenceTimeMs = getReferenceTimeMs(buf)
        this.feedbackPacketCount = getFeedbackPacketCount(buf)
        this.packetInfo = getPacketInfo(buf)
        recalcSize()
    }

    constructor(
        feedbackPacketCount: Int = -1
    ) {
        this.feedbackPacketCount = feedbackPacketCount
        this.packetInfo = PacketMap()
        recalcSize()
    }

    private fun recalcSize() {
        try {
            val deltaBlocksSize = packetInfo.keys
                    .map { tccSeqNum -> packetInfo.getStatusSymbol(tccSeqNum, referenceTimeMs) }
                    .map(PacketStatusSymbol::getDeltaSizeBytes)
                    .sum()

            // We always write status as vector chunks with 2 bit symbols
            val dataSize = 8 + // header values
                    // We can encode 7 statuses per packet chunk.  The '+ 6' is to
                    // account for integer division.
                    ((packetInfo.size + 6) / 7) * PacketStatusChunk.SIZE_BYTES +
                    deltaBlocksSize
            var paddingSize = 0
            while ((dataSize + paddingSize) % 4 != 0) {
                paddingSize++
            }
            size = dataSize + paddingSize
        } catch (e: Exception) {
            println("Error getting size of TCC fci: $e, buffer:\n${buf?.toHex()}")
            throw e
        }
    }

    fun addPacket(seqNum: Int, timestamp: Long) {
        if (this.referenceTimeMs == -1L) {
            this.referenceTimeMs = timestamp
        }
        packetInfo[seqNum] = timestamp
        recalcSize()
    }

    /**
     * Iterate over the pairs of (sequence number, timestamp) represented by this TCC FCI
     */
    fun forEach(action: (Int, Long) -> Unit) {
        packetInfo.forEach(action)
    }

    /**
     * How many packets are currently represented by this TCC
     */
    fun numPackets(): Int = packetInfo.size

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)
        try {
            setFeedbackPacketCount(b, this.feedbackPacketCount)
            try {
                setPacketInfo(b, this.packetInfo, referenceTimeMs)
            } catch (e: Exception) {
                println("BRIAN exception setting packet info to buffer: $e, buffer size: ${b.limit()}, needed size: ${this.size}\n" +
                        "the FCI was: $this")
                throw e
            }
            while (b.position() % 4 != 0) {
                b.put(0x00)
            }
        } catch (e: Exception) {
            println("Exception getting tcc buffer: $e")
            println("tcc detected size: ${this.size}, buffer capacity: ${b.capacity()}")
            throw e
        }
        b.rewind()
        buf = b
        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("TCC FCI")
            appendln("tcc seq num: $feedbackPacketCount")
            appendln("base sequence number: ${if (packetInfo.isNotEmpty()) packetInfo.firstKey() else -1}")
            appendln("packet status count: ${packetInfo.size}")
            appendln("reference time: $referenceTimeMs")
            appendln(packetInfo.toString())
            toString()
        }
    }
}

// https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.1
interface PacketStatusSymbol {
    fun hasDelta(): Boolean
    fun getDeltaSizeBytes(): Int
    val value: Int
}

object UnknownSymbol : PacketStatusSymbol {
    override val value: Int = -1
    override fun hasDelta(): Boolean = false
    override fun getDeltaSizeBytes(): Int = throw Exception()
}

class InvalidDeltaException(deltaMs: Double) : Exception("Invalid delta found: $deltaMs")

/**
 * Note that although the spec says:
 * "packet received" (0) and "packet not received" (1)
 * Chrome actually has it backwards ("packet received" uses the value
 * 1 and "packet not received" uses the value 0, so here we go against
 * the spec to match chrome.
 * Chrome file:
 * https://codesearch.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.cc?l=185&rcl=efbcb31cb67e3090b82c09ed5aabc4bbc53f37be
 */
//TODO: we will be unable to turn a received TCC packet with one bit symbols into one
// with 2 bit symbols because two bit symbols don't support a status of 'received but
// with no delta'
//NOTE: the old code interprets 'RECEIVED' for one bit symbols as having a small delta.  I haven't
// seen a part in the spec that defines this, but chrome appears to treat it this way
enum class OneBitPacketStatusSymbol(override val value: Int) : PacketStatusSymbol {
    RECEIVED(1),
    NOT_RECEIVED(0);

    companion object {
        private val map = OneBitPacketStatusSymbol.values().associateBy(OneBitPacketStatusSymbol::value);
        fun fromInt(type: Int): PacketStatusSymbol = map.getOrDefault(type, UnknownSymbol)
    }

    override fun hasDelta(): Boolean = this == RECEIVED
    override fun getDeltaSizeBytes(): Int = 1
}

enum class TwoBitPacketStatusSymbol(override val value: Int) : PacketStatusSymbol {
    NOT_RECEIVED(0),
    RECEIVED_SMALL_DELTA(1),
    RECEIVED_LARGE_OR_NEGATIVE_DELTA(2);

    companion object {
        private val map = TwoBitPacketStatusSymbol.values().associateBy(TwoBitPacketStatusSymbol::value)
        fun fromInt(type: Int): PacketStatusSymbol = map.getOrDefault(type, UnknownSymbol)
        // This method assumes only supports cases where the given delta falls into
        // one of the two delta ranges
        fun fromDeltaMs(deltaMs: Double): PacketStatusSymbol {
            return when (deltaMs) {
                in 0.0..63.75 -> TwoBitPacketStatusSymbol.RECEIVED_SMALL_DELTA
                in -8192.0..8191.75 -> TwoBitPacketStatusSymbol.RECEIVED_LARGE_OR_NEGATIVE_DELTA
                else -> throw InvalidDeltaException(deltaMs)
            }
        }
    }

    override fun hasDelta(): Boolean {
        return this == RECEIVED_SMALL_DELTA ||
                this == RECEIVED_LARGE_OR_NEGATIVE_DELTA
    }

    override fun getDeltaSizeBytes(): Int {
        return when (this) {
            RECEIVED_SMALL_DELTA -> 1
            RECEIVED_LARGE_OR_NEGATIVE_DELTA -> 2
            else -> 0
        }
    }
}

enum class PacketStatusChunkType(val value: Int) {
    UNKNOWN(-1),
    RUN_LENGTH_CHUNK(0),
    STATUS_VECTOR_CHUNK(1);

    companion object {
        private val map = PacketStatusChunkType.values().associateBy(PacketStatusChunkType::value);
        fun fromInt(type: Int): PacketStatusChunkType = map.getOrDefault(type, UNKNOWN)
    }
}

abstract class PacketStatusChunk : Iterable<PacketStatusSymbol> {
    companion object {
        const val SIZE_BYTES = 2
        fun getChunkType(buf: ByteBuffer): PacketStatusChunkType = PacketStatusChunkType.fromInt(buf.get(0).getBit(0))
        fun setChunkType(buf: ByteBuffer, chunkType: PacketStatusChunkType) {
            buf.putBits(0, 0, chunkType.value.toUByte(), 1)
        }

        /**
         * Note that the returned [PacketStatusChunk] will parse a status for every
         * available position in the status chunk.  Meaning that a [PacketStatusChunk]
         * doesn't know if the last N of the statuses it parses are actually invalid
         * (as can be the case with the last [PacketStatusChunk] in the list).  The
         * packet status count should always be used in determining how many valid
         * statuses are contained within a chunk.
         */
        fun parse(buf: ByteBuffer): PacketStatusChunk {
            return when (getChunkType(buf)) {
                PacketStatusChunkType.RUN_LENGTH_CHUNK -> RunLengthChunk(buf)
                PacketStatusChunkType.STATUS_VECTOR_CHUNK -> StatusVectorChunk(buf)
                else -> throw Exception("Unrecognized packet status chunk type: ${getChunkType(buf)}")
            }
        }
    }

    class PacketStatusChunkIterator(private val p: PacketStatusChunk) : Iterator<PacketStatusSymbol> {
        private var currSymbolIndex: Int = 0
        override fun hasNext(): Boolean = currSymbolIndex < p.numPacketStatuses()
        override fun next(): PacketStatusSymbol = p.getStatusByIndex(currSymbolIndex++)
    }

    abstract fun getChunkType(): PacketStatusChunkType
    /**
     * Get the number of packet statuses contained within this [PacketStatusChunk].  Note that this will
     * return the amount of packet statuses this chunk is *capable* of containing, not necessarily how many
     * valid ones it will actually contain (in the case of the last chunk).
     */
    abstract fun numPacketStatuses(): Int

    override fun iterator(): Iterator<PacketStatusSymbol> = PacketStatusChunkIterator(this)

    /**
     * Get the status of the [index]th represented in this [PacketStatusChunk]
     */
    abstract fun getStatusByIndex(index: Int): PacketStatusSymbol

    abstract fun getBuffer(): ByteBuffer
}

/**
 *  https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.3
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T| S |       Run Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * T = 0
 * Uses a single 2-bit symbol
 */
private class RunLengthChunk : PacketStatusChunk {
    var buf: ByteBuffer? = null
    var statusSymbol: PacketStatusSymbol
    var runLength: Int
    companion object {
        fun getStatusSymbol(buf: ByteBuffer): PacketStatusSymbol = TwoBitPacketStatusSymbol.fromInt(buf.get(0).getBits(1, 2).toUInt())
        fun setStatusSymbol(buf: ByteBuffer, statusSymbol: PacketStatusSymbol) {
            buf.putBits(0, 1, statusSymbol.value.toUByte(), 2)
        }

        fun getRunLength(buf: ByteBuffer): Int {
            val byte0 = buf.get(0) and 0x1F.toByte()
            val byte1 = buf.get(1)

            return (byte0.toUInt() shl 8) or (byte1.toUInt())
        }
        fun setRunLength(buf: ByteBuffer, runLength: Int) {
            val byte0 = ((runLength ushr 8) and 0x1F).toUByte()
            val byte0val = (buf.get(0).toUInt() or byte0.toUInt()).toUByte()
            val byte1 = (runLength and 0x00).toUByte()

            buf.put(0, byte0val)
            buf.put(1, byte1)
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        if (getChunkType(buf) != PacketStatusChunkType.RUN_LENGTH_CHUNK) {
            throw Exception("Invalid RunLengthChunk type ${getChunkType(buf)}")
        }
        this.statusSymbol = RunLengthChunk.getStatusSymbol(buf)
        this.runLength = RunLengthChunk.getRunLength(buf)
    }

    constructor(
        statusSymbol: PacketStatusSymbol = UnknownSymbol,
        runLength: Int = 0
    ) {
        this.statusSymbol = statusSymbol
        this.runLength = runLength
    }

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.RUN_LENGTH_CHUNK
    override fun numPacketStatuses(): Int = this.runLength
    /**
     * Every status symbol in a run-length encoding chunk is the same
     */
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = statusSymbol

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(PacketStatusChunk.SIZE_BYTES)
        }
        setChunkType(buf!!, PacketStatusChunkType.RUN_LENGTH_CHUNK)
        setStatusSymbol(buf!!, statusSymbol)
        setRunLength(buf!!, runLength)

        return buf!!.rewind() as ByteBuffer
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.4
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T|S|       symbol list         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * T = 1
 */
private class StatusVectorChunk : PacketStatusChunk {
    var buf: ByteBuffer? = null
    /**
     * How big each contained symbol size is, in bits
     * (Note that this is different from the symbol size
     * bit itself, which uses a '0' to denote 1 bit symbols
     * and a '1' to denote 2 bit symbols)
     */
    val symbolSizeBits: Int
    val packetStatusSymbols: List<PacketStatusSymbol>
    companion object {
        fun getSymbolsSizeBits(buf: ByteBuffer): Int {
            val symbolSizeBit = buf.get(0).getBits(1, 1).toUInt()
            return when (symbolSizeBit) {
                0 -> 1
                1 -> 2
                else -> throw Exception("Unrecognized symbol size: $symbolSizeBit")
            }
        }
        fun setSymbolSizeBits(buf: ByteBuffer, symbolSizeBits: Int) {
            val symbolSizeBit = when(symbolSizeBits) {
                1 -> 0
                2 -> 1
                else -> throw Exception("Unsupported symbol size: $symbolSizeBits bits")
            }
            buf.putBits(0, 1, symbolSizeBit.toUByte(), 1)
        }

        fun getSymbolList(buf: ByteBuffer): List<PacketStatusSymbol> {
            val symbolsSize = getSymbolsSizeBits(buf)
            val symbols = mutableListOf<PacketStatusSymbol>()
            for (bitIndex in 2..15 step symbolsSize) {
                val currByte = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                val symbol = when (symbolsSize) {
                    1 -> OneBitPacketStatusSymbol.fromInt((buf.get(currByte).getBits(bitInByteIndex, symbolsSize)).toUInt())
                    2 -> TwoBitPacketStatusSymbol.fromInt((buf.get(currByte).getBits(bitInByteIndex, symbolsSize)).toUInt())
                    else -> TODO()
                }
                symbols.add(symbol)
            }
            return symbols
        }
        fun setSymbolList(buf: ByteBuffer, statusSymbols: List<PacketStatusSymbol>, symbolSizeBits: Int) {
            for (i in 0 until statusSymbols.size) {
                val symbol = statusSymbols.get(i)
                val bitIndex = 2 + (i * symbolSizeBits)
                val byteIndex = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                buf.putBits(byteIndex, bitInByteIndex, symbol.value.toUByte(), symbolSizeBits)
            }
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.symbolSizeBits = getSymbolsSizeBits(buf)
        this.packetStatusSymbols = getSymbolList(buf)
    }

    constructor(
        symbolSize: Int = 0,
        packetStatusSymbols: List<PacketStatusSymbol> = listOf()
    ) {
        this.symbolSizeBits = symbolSize
        this.packetStatusSymbols = packetStatusSymbols
    }

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.STATUS_VECTOR_CHUNK
    override fun numPacketStatuses(): Int {
        return when (symbolSizeBits) {
            1 -> 14
            2 -> 7
            else -> throw Exception("Unrecognized symbol size: $symbolSizeBits")
        }
    }
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = packetStatusSymbols[index]

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(PacketStatusChunk.SIZE_BYTES)
        }
        setChunkType(buf!!, PacketStatusChunkType.STATUS_VECTOR_CHUNK)
        setSymbolSizeBits(buf!!, symbolSizeBits)
        setSymbolList(buf!!, packetStatusSymbols, symbolSizeBits)

        return buf!!.rewind() as ByteBuffer
    }
}

abstract class ReceiveDelta {
    abstract var deltaMs: Double //TODO: should we be able to hold this as a long? don't think a double makes sense?
    abstract fun getBuffer(): ByteBuffer
    abstract fun getSize(): Int

    companion object {
        fun parse(buf: ByteBuffer, deltaSizeBytes: Int): ReceiveDelta {
            return when (deltaSizeBytes) {
                EightBitReceiveDelta.SIZE_BYTES -> EightBitReceiveDelta(buf)
                SixteenBitReceiveDelta.SIZE_BYTES -> SixteenBitReceiveDelta(buf)
                else -> throw Exception("Unsupported receive delta size: $deltaSizeBytes bytes")
            }
        }
        fun create(delta: Double): ReceiveDelta {
            return when (delta) {
                in 0.0..63.75 -> EightBitReceiveDelta(delta)
                in -8192.0..8191.75 -> SixteenBitReceiveDelta(delta)
                else -> throw Exception("Unsupported delta value: $delta")
            }
        }
    }

    override fun toString(): String {
        return "delta: ${deltaMs}ms"
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.5
 * If the "Packet received, small delta" symbol has been appended to
 * the status list, an 8-bit unsigned receive delta will be appended
 * to recv delta list, representing a delta in the range [0, 63.75]
 * ms.
 */
class EightBitReceiveDelta : ReceiveDelta {
    var buf: ByteBuffer? = null
    override var deltaMs: Double

    companion object {
        const val SIZE_BYTES = 1

        /**
         * The value written in the field is represented as multiples of 250us
         */
        fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.get().toUInt()
            val uSecs = uSecMultiple * 250.0
            return uSecs / 1000.0
        }
        fun setDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.put(uSecMultiple.toByte())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, EightBitReceiveDelta.SIZE_BYTES)
        b.rewind()
        setDeltaMs(b, deltaMs)
        b.rewind()
        buf = b

        return b
    }

    override fun getSize(): Int = EightBitReceiveDelta.SIZE_BYTES
}

/**
 * If the "Packet received, large or negative delta" symbol has been
 * appended to the status list, a 16-bit signed receive delta will be
 * appended to recv delta list, representing a delta in the range
 * [-8192.0, 8191.75] ms.
 */
class SixteenBitReceiveDelta : ReceiveDelta {
    var buf: ByteBuffer? = null
    override var deltaMs: Double

    companion object {
        const val SIZE_BYTES = 2

        /**
         * The value written in the field is represented as multiples of 250us
         */
        fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.getShort().toInt()
            val uSecs = uSecMultiple * 250.0
            return uSecs / 1000.0
        }
        fun setDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.putShort(uSecMultiple.toShort())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(SixteenBitReceiveDelta.SIZE_BYTES)
        }
        setDeltaMs(this.buf!!, deltaMs)

        return this.buf!!.rewind() as ByteBuffer
    }
    override fun getSize(): Int = SixteenBitReceiveDelta.SIZE_BYTES
}
