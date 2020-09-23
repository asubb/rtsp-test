package rsp.test

import assertk.all
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import assertk.catch
import org.spekframework.spek2.Spek
import org.spekframework.spek2.lifecycle.CachingMode
import org.spekframework.spek2.style.specification.describe
import rtsp.test.RtpMapping
import rtsp.test.RtspRecordControllerHandler
import rtsp.test.RtspServer
import rtsp.test.RtspSessionClient
import java.io.*


object RtspClientServerSpec : Spek({
    describe("Base RECORD flow") {
        val content = "1234567890abcdefghijklmnopqrstuvwxyz".toByteArray()

        val buffer by memoized(CachingMode.EACH_GROUP) {
            ByteArrayOutputStream()
        }

        val mappingContainer by memoized(CachingMode.EACH_GROUP) {
            Array<RtpMapping?>(1) { null }
        }

        val bufferHandler = { _: Long, _: RtpMapping, buf: ByteArray ->
            buffer.write(buf)
        }

        val tearDownHandler = { _: Long, mapping: RtpMapping ->
            mappingContainer[0] = mapping
        }

        val server = Thread {
            RtspServer(RtspRecordControllerHandler(bufferHandler, tearDownHandler), 8888).start(true)
        }.also { it.start() }

        val client by memoized(CachingMode.EACH_GROUP) { RtspSessionClient("localhost", 8888, "/test") }

        afterGroup {
            server.interrupt()
            client.close()
        }

        it("should run OPTIONS") {
            assertThat(catch { client.options().get() }).isNull()
        }

        val formatId = 96
        val channel = 0
        it("should announce the content") {
            assertThat(catch { client.announce("test track", "L8/44100/1", formatId, channel).get() }).isNull()
        }

        it("should setup the session") {
            assertThat(catch { client.setup("record", channel).get() }).isNull()
        }

        it("should initiate record of the session") {
            assertThat(catch { client.record().get() }).isNull()
        }

        it("should stream data") {
            assertThat(catch { client.streamData(formatId, channel, ByteArrayInputStream(content)) }).isNull()
        }

        it("should tear down the session") {
            assertThat(catch { client.tearDown().get() }).isNull()
        }

        it("should have written the content") {
            assertThat(buffer.toByteArray()).isEqualTo(content)
        }

        it("should have specified mapping") {
            assertThat(mappingContainer[0]).isNotNull().all {
                prop("encoding") { it.encoding.toLowerCase() }.isEqualTo("l8")
                prop("clockRate") { it.clockRate }.isEqualTo(44100)
                prop("encodingParameters") { it.encodingParameters?.toLowerCase() }.isEqualTo("1")
            }
        }
    }
})

