package rsp.test

import assertk.assertThat
import assertk.assertions.isNull
import assertk.catch
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.rtsp.*
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import mu.KotlinLogging
import org.spekframework.spek2.Spek
import org.spekframework.spek2.lifecycle.CachingMode
import org.spekframework.spek2.style.specification.describe
import rtsp.test.runServer
import java.io.*
import java.nio.charset.Charset
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random


object ServerSpec : Spek({
    describe("RECORD flow") {
        val server = Thread { runServer(8888) }.also { it.start() }

        val client by memoized(CachingMode.EACH_GROUP) { RtspSessionClient("localhost", 8888, "/test") }

        afterGroup {
            server.interrupt()
            client.close()
        }

        it("should run OPTIONS") {
            assertThat(catch { client.options().get() }).isNull()
        }

        it("should announce the content") {
            assertThat(catch { client.announce("test track", "L8/44100/1", 96, 0).get() }).isNull()
        }

        it("should setup the session") {
            assertThat(catch { client.setup("record", 0, 1).get() }).isNull()
        }

        it("should initiate record of the session") {
            assertThat(catch { client.record().get() }).isNull()
        }

        it("should stream data") {
            val s = "1234567890abcdefghijklmnopqrstuvwxyz"
            assertThat(catch { client.streamData(96, 0, ByteArrayInputStream(s.toByteArray())) }).isNull()
        }

        it("should tear down the session") {
            assertThat(catch { client.tearDown().get() }).isNull()
        }
    }
})

class RtspSessionClient(
        private val host: String,
        private val port: Int,
        path: String
) : Closeable {

    private val log = KotlinLogging.logger { }

    private val executor = Executors.newSingleThreadExecutor()
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()

    private val requestSemaphore = Semaphore(1)

    @Volatile
    private var resultLatch = CountDownLatch(1)
    private val result = AtomicReference<Any?>(null)
    private val client = client()
    private val uri = "rtsp://$host:$port$path"

    private val sessionId = Random.nextInt(Int.MAX_VALUE)
    private val cseq = AtomicInteger(1)
    private val streamCounter = AtomicInteger(Random.nextInt(Int.MAX_VALUE / 4))

    inner class RtspClientHandler : ChannelInboundHandlerAdapter() {

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            log.info { "RtspClientHandler.channelRead(ctx=$ctx,msg=$msg)" }
            if (msg is FullHttpResponse) {
                result.set(msg)
            } else {
                result.set(UnsupportedOperationException("$msg is unsupported"))
            }
            resultLatch.countDown()
        }
    }

    fun options(): Future<Unit> {
        val request = DefaultFullHttpRequest(RtspVersions.RTSP_1_0, RtspMethods.OPTIONS, "*")
        request.headers().add(RtspHeaderNames.CSEQ, cseq.getAndIncrement())
        return doRequest<FullHttpResponse, Unit>(request) {
            log.info { "OPTIONS request resulted with $it" }
        }
    }

    fun announce(
            title: String,
            format: String,
            formatId: Int,
            port: Int,
    ): Future<Unit> {
        val buffer = client.alloc().buffer()
        val contentLength = buffer.writeCharSequence("""
            v=0
            o=$title
            m=audio $port RTP/AVP $formatId
            a=rtpmap:$formatId $format
        """.trimIndent(), Charset.defaultCharset())
        val request = DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0,
                RtspMethods.ANNOUNCE,
                uri,
                buffer
        )
        request.headers()
                .add(RtspHeaderNames.CSEQ, cseq.getAndIncrement())
                .add(RtspHeaderNames.SESSION, sessionId)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/sdp")
                .add(HttpHeaderNames.CONTENT_LENGTH, contentLength)
        return doRequest<FullHttpResponse, Unit>(request) {
            log.info { "ANNOUNCE request resulted with $it" }
        }
    }

    fun setup(mode: String, dataPort: Int, managePort: Int): Future<Unit> {
        val request = DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0,
                RtspMethods.SETUP,
                uri
        )
        request.headers()
                .add(RtspHeaderNames.CSEQ, cseq.getAndIncrement())
                .add(RtspHeaderNames.SESSION, sessionId)
                .add(RtspHeaderNames.TRANSPORT, "RTP/AVP/TCP;unicast;mode=$mode;interleaved=$dataPort-$managePort")
        return doRequest<FullHttpResponse, Unit>(request) {
            log.info { "SETUP request resulted with $it" }
        }
    }

    fun record(): Future<Unit> {
        val request = DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0,
                RtspMethods.RECORD,
                uri
        )
        request.headers()
                .add(RtspHeaderNames.CSEQ, cseq.getAndIncrement())
                .add(RtspHeaderNames.SESSION, sessionId)
        return doRequest<FullHttpResponse, Unit>(request) {
            log.info { "RECORD request resulted with $it" }
        }
    }

    fun streamData(formatId: Int, channel: Int, stream: InputStream, maxPacketSize: Int = 1024) {
        val buf = client.alloc().buffer()
        val b = ByteArray(maxPacketSize)
        val packetHeader = ByteArray(4)
        packetHeader[0] = '$'.toByte()
        packetHeader[1] = channel.toByte()
        val rtpHeaderSize = 12
        val rtpHeader = ByteArray(rtpHeaderSize)
        rtpHeader[0] = (
                (2 shl 6) or // version
                        (0 shl 5) or // padding
                        (0 shl 4) or // extension
                        (0) // csrc count
                ).toByte()
        rtpHeader[1] = (
                (0 shl 7) or // marker
                        (formatId and 0x7F) // payload type
                ).toByte()
        BufferedInputStream(stream).use { reader ->
            val bytesRead = reader.read(b)
            val contentSize = bytesRead + rtpHeaderSize
            packetHeader[2] = (contentSize ushr 8 and 0xFF).toByte()
            packetHeader[3] = (contentSize and 0xFF).toByte()
            val counter = streamCounter.getAndIncrement()
            rtpHeader[2] = (counter ushr 8 and 0xFF).toByte()
            rtpHeader[3] = (counter and 0xFF).toByte()
            val timestamp = (System.currentTimeMillis() % Int.MAX_VALUE.toLong()).toInt()
            rtpHeader[4] = (timestamp ushr 24 and 0xFF).toByte()
            rtpHeader[5] = (timestamp ushr 16 and 0xFF).toByte()
            rtpHeader[6] = (timestamp ushr 8 and 0xFF).toByte()
            rtpHeader[7] = (timestamp and 0xFF).toByte()
            val ssrc = sessionId
            rtpHeader[8] = (ssrc ushr 24 and 0xFF).toByte()
            rtpHeader[9] = (ssrc ushr 16 and 0xFF).toByte()
            rtpHeader[10] = (ssrc ushr 8 and 0xFF).toByte()
            rtpHeader[11] = (ssrc and 0xFF).toByte()

            buf.writeBytes(packetHeader)
            buf.writeBytes(rtpHeader)
            buf.writeBytes(b, 0, bytesRead)
            client.writeAndFlush(buf).sync()
        }
    }

    fun tearDown(): Future<Unit> {
        val request = DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0,
                RtspMethods.TEARDOWN,
                uri
        )
        request.headers()
                .add(RtspHeaderNames.CSEQ, cseq.getAndIncrement())
                .add(RtspHeaderNames.SESSION, sessionId)
        return doRequest<FullHttpResponse, Unit>(request) {
            log.info { "TEARDOWN request resulted with $it" }
        }
    }

    override fun close() {
        client.close().sync()
        workerGroup.shutdownGracefully().sync()
        executor.shutdown()
        if (!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
            executor.shutdownNow()
        }
    }

    private fun <I, O> doRequest(request: HttpRequest, resultHandler: (I) -> O): Future<O> {
        if (!requestSemaphore.tryAcquire()) throw IllegalStateException("Another request is in progress")
        return executor.submit(Callable {
            resultLatch = CountDownLatch(1)
            client.writeAndFlush(request)
            val result = awaitResult<I>()
            requestSemaphore.release()
            resultHandler(result)
        })
    }

    private fun <T> awaitResult(timeoutMs: Long = 5000): T {
        if (resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            val result = checkNotNull(result.get()) { "Result is returned as null" }
            if (result is Exception) throw result
            @Suppress("UNCHECKED_CAST")
            return result as T
        } else {
            throw TimeoutException("Didn't get result within $timeoutMs ms")
        }
    }

    private fun client(): Channel {
        val b = Bootstrap()
        b.group(workerGroup)
        b.channel(NioSocketChannel::class.java)
        b.option(ChannelOption.SO_KEEPALIVE, true)
        b.handler(object : ChannelInitializer<SocketChannel>() {
            @Throws(Exception::class)
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline()
                        .addLast(LoggingHandler(LogLevel.INFO))
                        .addLast(RtspEncoder())
                        .addLast(RtspDecoder())
                        .addLast(HttpObjectAggregator(4 * 1024))
                        .addLast(RtspClientHandler())
            }
        })

        // Start the client.
        return b.connect(host, port).sync().channel()
    }
}

