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
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


var recording = AtomicBoolean(false)
var contentFile = File.createTempFile("some-file", ".wav.tmp")
var stream: OutputStream? = null
var dataChannel: Int? = null
var manageChannel: Int? = null
var channelMappings: Map<Int, RtpMap>? = null
var clientLastSeen: Long = Long.MAX_VALUE

data class MediaAnnouncement(
        val media: String,
        val port: Int,
        val numberOfPorts: Int,
        val transport: String,
        val fmtList: List<Int>
)

data class RtpMap(
        val payloadType: Int,
        val encoding: String,
        val clockRate: Int,
        val encodingParameters: String?
)

class Controller : ChannelInboundHandlerAdapter() {

    val log = KotlinLogging.logger { }
    val sched = Executors.newSingleThreadScheduledExecutor()

    init {
        sched.scheduleAtFixedRate({
            if (System.currentTimeMillis() - clientLastSeen > 5000) {
                doTearDown()
                sched.shutdownNow()
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

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
                                    RtspMethods.RECORD,
                                    RtspMethods.ANNOUNCE,
                                    RtspMethods.OPTIONS,
                            ).joinToString(", ")
                    )
                }
                RtspMethods.ANNOUNCE -> {
                    val buffer = ByteArrayOutputStream()
                    msg.content().readBytes(buffer, msg.content().readableBytes())
                    val content = String(buffer.toByteArray())
                            .split("[\r\n]+".toRegex())
                            .filterNot(String::isEmpty)
                    log.info { "Announced the following content:\n${content.joinToString("\n")}" }

                    // search for media name and transport address
                    require(content.single { it.startsWith("v=") } == "v=0") { "Only version 0 is supported" }
                    val announced = content.filter { it.startsWith("m=") }
                            .map { mediaAnnouncementString ->
                                val d = mediaAnnouncementString.removePrefix("m=").split(" ")
                                require(d.size >= 4) { "The Media Announcement `$mediaAnnouncementString` doesn't have all expected elements (>=4)." }
                                val media = d[0]
                                val (port, portNumber) = if (d[1].indexOf('/') < 0) {
                                    listOf(d[1].toInt(), 1)
                                } else {
                                    d[1].split("/", limit = 2).map { it.toInt() }
                                }
                                val transport = d[2]
                                val fmtList = d.subList(3, d.size).map { it.toInt() }
                                MediaAnnouncement(media, port, portNumber, transport, fmtList)
                            }

                    log.info { "Announced media: $announced" }

                    val rtpMaps = content.filter { it.startsWith("a=rtpmap:") }
                            .map { rtpMapString ->
                                val (payloadType, format) = rtpMapString.removePrefix("a=rtpmap:")
                                        .split(" ", limit = 2)
                                        .let { Pair(it[0].toInt(), it[1]) }
                                val (encoding, clockRate, encodingParameters) = format.split("/")
                                        .let { Triple(it[0], it[1].toInt(), if (it.size > 2) it[2] else null) }
                                RtpMap(payloadType, encoding, clockRate, encodingParameters)
                            }
                    log.info { "RTP mappings: $rtpMaps" }

                    // find all mappings for media
                    channelMappings = announced.map {
                        require(it.fmtList.size == 1) { "fmtList != 1 is not supported" }
                        val fmt = it.fmtList.first()
                        require(fmt >= 96) { "Built in formats are not supported, only custom >= 96" }
                        val rtmMap = checkNotNull(rtpMaps.firstOrNull { it.payloadType == fmt }) { "Format $fmt is not found among mappings: $rtpMaps" }

                        it.port to rtmMap
                    }.toMap()
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
                    val (data, manage) = interleaved.split("-", limit = 2).map { it.toInt() }
                    dataChannel = data
                    manageChannel = manage
                    if (mode?.toLowerCase() != "record") throw UnsupportedOperationException("mode=$mode is not supported")

                    response.headers().add(RtspHeaderNames.TRANSPORT, transport)
                }
                RtspMethods.RECORD -> {
                    recording.set(true)
                    stream = FileOutputStream(contentFile)
                    log.info { "Started streaming to temporary file $contentFile" }
                }
                RtspMethods.TEARDOWN -> {
                    doTearDown()
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

    private fun doTearDown() {
        log.info { "Finished streaming to temporary file $contentFile" }
        stream?.close()
        val ba = contentFile.readBytes()
        val f = File.createTempFile("stream", ".wav")
        val mapping = checkNotNull(channelMappings?.get(dataChannel))
        val bitDepth = when (mapping.encoding.toLowerCase()) {
            "l8" -> BitDepth.BIT_8
            "l16" -> BitDepth.BIT_16
            else -> throw UnsupportedOperationException(mapping.encoding)
        }
        val sampleRate = mapping.clockRate.toFloat()
        val channels = mapping.encodingParameters?.toInt() ?: 1
        f.writeBytes(WavHeader(bitDepth, sampleRate, channels, ba.size).header() + ba)
        log.info { "Saved output to file $f" }
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
                                                        }.padEnd(16 * 3 - 1, ' ') + "|" +
                                                                bytes.map { byte ->
                                                                    if (byte in 0x20..0xCF) byte.toChar() else '.'
                                                                }.joinToString("").padEnd(16, ' ') + "|"

                                                    }.joinToString("\n")
                                }
                                // read RTP header: https://tools.ietf.org/html/rfc3550#section-5.1
                                val i1 = currentBuffer.take(4)
                                        .mapIndexed { j, b -> (b.toLong() and 0xFF) shl (8 * (3 - j)) }
                                        .reduce { acc, j -> acc or j }
                                val version = (i1 ushr 30) and 0x03
                                require(version == 2L) { "RTPHeader.version=$version. Version 2 is supported only." }
                                val padding = (i1 ushr 29) and 0x01
                                val extension = (i1 ushr 28) and 0x01
                                require(extension == 0L) { "RTPHeader.extension=$extension. Non-0 value is not implemented." }
                                val csrcCount = (i1 ushr 24) and 0x07
                                require(csrcCount == 0L) { "RTPHeader.csrcCount=$csrcCount. Non-0 is not implemented." }
                                val marker = (i1 ushr 23) and 0x01
//                                require(marker == 0L) { "RTPHeader.marker=$marker. Non-0 value is not implemented." }
                                val payload = (i1 ushr 16) and 0x7F
                                val sequenceNumber = i1 and 0xFFFF
                                val timestamp = currentBuffer.drop(4).take(4)
                                        .mapIndexed { j, b -> (b.toLong() and 0xFF) shl (8 * (3 - j)) }
                                        .reduce { acc, j -> acc or j }
                                val ssrc = currentBuffer.drop(8).take(4)
                                        .mapIndexed { j, b -> (b.toLong() and 0xFF) shl (8 * (3 - j)) }
                                        .reduce { acc, j -> acc or j }
                                val csrc = (0 until csrcCount).map {
                                    currentBuffer.drop(12 + 4 * i).take(4)
                                            .mapIndexed { j, b -> (b.toLong() and 0xFF) shl (8 * (3 - j)) }
                                            .reduce { acc, j -> acc or j }
                                }

                                log.info {
                                    """
                                        RTP Header:
                                            version=$version
                                            padding=$padding
                                            extension=$extension
                                            csrcCount=$csrcCount
                                            marker=$marker
                                            payload=$payload
                                            sequenceNumber=$sequenceNumber
                                            timestamp=$timestamp
                                            ssrc=$ssrc
                                            csrc=$csrc
                                    """.trimIndent()
                                }

                                val rtpHeaderSize = 12 + csrcCount.toInt()
                                if (currentChannel == dataChannel) {
                                    clientLastSeen = System.currentTimeMillis()
                                    stream?.write(currentBuffer
                                            .copyOfRange(rtpHeaderSize, currentBuffer.size)
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
    runServer()
}

fun runServer(port: Int = 12345) {
    val bossGroup: EventLoopGroup = NioEventLoopGroup()
    val workerGroup: EventLoopGroup = NioEventLoopGroup()

    try {
        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {

                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
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
        val f: ChannelFuture = b.bind(port).sync()

        // Wait until the server socket is closed.
        // In this example, this does not happen, but you can do that to gracefully
        // shut down your server.
        f.channel().closeFuture().sync()
    } finally {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}