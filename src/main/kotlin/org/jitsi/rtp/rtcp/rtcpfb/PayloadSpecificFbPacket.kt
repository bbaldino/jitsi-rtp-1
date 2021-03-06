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

import org.jitsi.rtp.rtcp.RtcpHeader
import java.nio.ByteBuffer

abstract class PayloadSpecificFbPacket : RtcpFbPacket {
    companion object {
        const val PT = 206
    }
    constructor(buf: ByteBuffer) : super(buf) {
        super.header.packetType = PT
    }

    constructor(
        header: RtcpHeader = RtcpHeader(),
        mediaSourceSsrc: Long = 0
    ) : super(header, mediaSourceSsrc) {
        super.header.packetType = PT
    }
}
