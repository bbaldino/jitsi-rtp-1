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

package org.jitsi.test_helpers.matchers

import io.kotlintest.Matcher
import io.kotlintest.Result
import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer

fun haveSameContentAs(other: ByteBuffer) = object : Matcher<ByteBuffer> {
    override fun test(value: ByteBuffer): Result {
        return Result(value.compareToFromBeginning(other) == 0,
            "Buffer\n${value.toHex()} should have equaled buffer\n${other.toHex()}",
            "Buffer\n${value.toHex()} should not have equaled buffer\n${other.toHex()}"
        )
    }
}