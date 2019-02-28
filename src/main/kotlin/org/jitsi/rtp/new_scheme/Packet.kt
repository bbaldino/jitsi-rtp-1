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

package org.jitsi.rtp.new_scheme

import org.jitsi.rtp.Serializable
import java.nio.ByteBuffer

interface CanBecomeModifiable<ModifiableType : Modifiable> {
    fun modifyInPlace(): ModifiableType

    fun getModifiableCopy(): ModifiableType
}

interface CanBecomeReadOnly<ReadOnlyType : ReadOnly> {
    fun toReadOnly(): ReadOnlyType
}

interface ReadOnly

interface Modifiable

interface ModifiablePacket : Modifiable

abstract class ReadOnlyPacket : ReadOnly, Serializable {
    protected abstract val dataBuf: ByteBuffer
    open val sizeBytes: Int
        get() = dataBuf.limit()

    override fun getBuffer(): ByteBuffer = dataBuf.asReadOnlyBuffer()
}

interface Convertable<U> {
    fun <T : U >convertTo(builder: ConstructableFromBuffer<T>): T
}

interface ConstructableFromBuffer<T> {
    fun fromBuffer(buf: ByteBuffer): T
}
