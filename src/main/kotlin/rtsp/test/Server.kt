package rtsp.test

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.rtsp.*
import io.netty.handler.logging.LoggingHandler
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean


var recording = AtomicBoolean(false)
var contentFile = File.createTempFile("some-file", ".wav.tmp")
var stream: OutputStream? = null

class Controller : ChannelInboundHandlerAdapter() {
    val log = KotlinLogging.logger { }
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        log.info { "Received $msg" }
        if (msg is FullHttpRequest) {
            log.info { "Handling method ${msg.method()} on ${msg.uri()}" }
            val response = DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK)
            msg.headers()[RtspHeaderNames.CSEQ]?.let { response.headers().add(RtspHeaderNames.CSEQ, it) }
            when (msg.method()) {
                RtspMethods.OPTIONS -> {
                    response.headers().add(
                            RtspHeaderNames.PUBLIC,
                            listOf(
                                    RtspMethods.DESCRIBE,
                                    RtspMethods.SETUP,
                                    RtspMethods.TEARDOWN,
                                    RtspMethods.PLAY,
                                    RtspMethods.PAUSE,
                            ).joinToString(", ")
                    )
                }
                RtspMethods.ANNOUNCE -> {
                    val buffer = ByteArrayOutputStream()
                    msg.content().readBytes(buffer, msg.content().readableBytes())
                    val content = String(buffer.toByteArray())
                            .split("\r\n")
                            .filterNot { it.isEmpty() }
                    log.info { "Announced the following content $content" }
                }
                RtspMethods.SETUP -> {
                    val transport = msg.headers()[RtspHeaderNames.TRANSPORT]
                    val values = transport.split(";")
                    if (values[0] != "RTP/AVP/TCP") throw UnsupportedOperationException("Only TCP is supported")
                    if (values[1] != "unicast") throw UnsupportedOperationException("Only `unicast` is supported")
                    val mode = values.firstOrNull { it.startsWith("mode") }
                            ?.split("=", limit = 2)
                            ?.get(1)
                    val interleaved = values.firstOrNull { it.startsWith("interleaved") }
                            ?.split("=", limit = 2)
                            ?.get(1)
                            ?: throw UnsupportedOperationException("interleaved must be specified")

                    if (mode?.toLowerCase() != "record") throw UnsupportedOperationException("mode=$mode is not supported")

                    response.headers().add(RtspHeaderNames.TRANSPORT, transport)
                }
                RtspMethods.RECORD -> {
                    recording.set(true)
                    stream = FileOutputStream(contentFile)
                    log.info { "Started streaming to temporary file $contentFile" }
                }
                RtspMethods.TEARDOWN -> {
                    log.info { "Finished streaming to temporary file $contentFile" }
                    stream?.close()
                    val ba = contentFile.readBytes()
                    val f = File.createTempFile("stream", ".wav")
                    f.writeBytes(WavHeader(BitDepth.BIT_16, 44100.0f, 1, ba.size).header() + ba)
                    log.info { "Saved output to file $f" }
                }
                else -> throw UnsupportedOperationException()
            }
            log.info { "Responding $response" }
            ctx.write(response)
            ctx.flush()
        } else if (msg is HttpContent) {
            log.info { "Handling message ${msg}" }
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Close the connection when an exception is raised.
        log.error(cause) { "Error in $ctx" }
        ctx.close()
    }
}

var bytesLeftToRead: Int = 0
var currentBuffer: ByteArray = ByteArray(0)
var currentChannel: Int = 0
var currentPacketSize: Int = 0

// 0 - searching for the packet,
// 1 - waiting for the channel,
// 2 - waiting for the packet size byte 0,
// 3 - waiting for the packet size byte 1,
// 4 - reading the buffer
var currentState: Int = 0

class Receiver : ChannelInboundHandlerAdapter() {

    private val log = KotlinLogging.logger { }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        log.info { "Received $msg" }
        if (!recording.get()) {
            ctx.fireChannelRead(msg)
        } else if (msg is ByteBuf) {
            val buffer = ByteArrayOutputStream()
            msg.readBytes(buffer, msg.readableBytes())
            val bytes = buffer.toByteArray()
            var i = 0
            if (bytes.copyOfRange(0, 8).contentEquals("TEARDOWN".toByteArray())) {
                log.info { "Got TEARDOWN command. Escalating" }
                recording.set(false)
                ctx.fireChannelRead(Unpooled.copiedBuffer(bytes))
            } else {
                msg.release()
                ctx.fireChannelReadComplete()
                while (i < bytes.size) {
                    when (currentState) {
                        0 -> {
                            if (bytes[i] == '$'.toByte()) currentState++
                        }
                        1 -> {
                            currentChannel = bytes[i].toInt() and 0xFF
                            currentState++
                        }
                        2 -> {
                            currentPacketSize = (bytes[i].toInt() and 0xFF) shl 8
                            currentState++
                        }
                        3 -> {
                            currentPacketSize = currentPacketSize or (bytes[i].toInt() and 0xFF)
                            currentState++
                            log.info { "Located packet of channel=$currentChannel, bytesInThePacket=$currentPacketSize." }
                            currentBuffer = ByteArray(currentPacketSize)
                            bytesLeftToRead = currentPacketSize
                        }
                        4 -> {
                            if (bytesLeftToRead > 0) {
                                currentBuffer[currentPacketSize - bytesLeftToRead] = bytes[i]
                                bytesLeftToRead--
                            }
                            if (bytesLeftToRead == 0) {
                                log.info {
                                    "Read the packet of channel=$currentChannel, bytesInThePacket=$currentPacketSize:\n" +
                                            currentBuffer.asSequence().windowed(16, 16, true)
                                                    .map { bytes ->
                                                        bytes.joinToString(" ") { byte ->
                                                            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
                                                        } + "|" +
                                                                bytes.map { byte ->
                                                                    if (byte in 0x20..0xCF) byte.toChar() else '.'
                                                                }.joinToString("") + "|"

                                                    }.joinToString("\n")
                                }
                                if (currentChannel == 0) {
                                    stream?.write(currentBuffer
                                            .copyOfRange(12, currentBuffer.size) // ???? why??
                                    )
                                    stream?.flush()
                                }
                                currentState = 0
                            }
                        }
                        else -> throw UnsupportedOperationException("state=$currentState")
                    }
                    i++
                }
                log.info { "Finished the buffer with state=$currentState, bytesLeftToRead=$bytesLeftToRead" }
            }
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Close the connection when an exception is raised.
        log.error(cause) { "Error in $ctx" }
        ctx.close()
    }
}

fun main() {
    val bossGroup: EventLoopGroup = NioEventLoopGroup()
    val workerGroup: EventLoopGroup = NioEventLoopGroup()

    try {
        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {

                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
//                                .addLast(LoggingHandler())
                                .addLast(Receiver())
                                .addLast(RtspDecoder())
                                .addLast(HttpObjectAggregator(4 * 1024))
                                .addLast(RtspEncoder())
                                .addLast(Controller())
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

        // Bind and start to accept incoming connections.
        val f: ChannelFuture = b.bind(12345).sync()

        // Wait until the server socket is closed.
        // In this example, this does not happen, but you can do that to gracefully
        // shut down your server.
        f.channel().closeFuture().sync()
    } finally {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}