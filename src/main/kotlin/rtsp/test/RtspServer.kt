package rtsp.test

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import mu.KotlinLogging
import java.io.*
import java.util.concurrent.ConcurrentHashMap

class RtspServer(
        val rtspRecordControllerHandler: RtspRecordControllerHandler,
        val port: Int,
) : Closeable {

    private val bossGroup: EventLoopGroup = NioEventLoopGroup()
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()
    private var server: ChannelFuture? = null

    fun start(andWait: Boolean = false) {
        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {

                    override fun initChannel(ch: SocketChannel) {
                        rtspRecordControllerHandler.attachTo(ch.pipeline())
                        ch.pipeline().addFirst(LoggingHandler(RtspServer::class.java, LogLevel.TRACE))
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

        // Bind and start to accept incoming connections.
        server = b.bind(port)
        if (andWait) {
            server!!.channel().closeFuture().sync()
        }
    }


    override fun close() {
        server?.channel()?.closeFuture()?.sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}

fun main() {
    val log = KotlinLogging.logger { }

    val streams = ConcurrentHashMap<Long, Pair<File, OutputStream>>()

    RtspRecordControllerHandler(
            bufferHandler = { sid, _, buf ->
                val stream = streams.computeIfAbsent(sid) {
                    val file = File.createTempFile("content", ".tmp")
                    file to BufferedOutputStream(FileOutputStream(file))
                }
                stream.second.write(buf)
            },
            tearDownHandler = { sid, mapping ->
                val stream = checkNotNull(streams[sid]) { "no streams were created for $sid" }
                stream.second.close()
                val extension = when (mapping.encoding.toLowerCase()) {
                    "l8", "l16" -> ".wav"
                    else -> throw UnsupportedOperationException("$mapping")
                }
                val content = stream.first.readBytes()
                val sampleRate = mapping.clockRate.toFloat()
                val numberOfChannels = mapping.encodingParameters?.toInt() ?: 1
                val dataSize = content.size
                val header = when (mapping.encoding.toLowerCase()) {
                    "l8" -> WavHeader(BitDepth.BIT_8, sampleRate, numberOfChannels, dataSize).header()
                    "l16" -> WavHeader(BitDepth.BIT_16, sampleRate, numberOfChannels, dataSize).header()
                    else -> throw UnsupportedOperationException("$mapping")
                }
                val f = File.createTempFile("output", extension)
                f.writeBytes(header + content)
                log.info { "Written file $f" }
            },
            sessionTtl = 5_000
    ).use { rtspRecordControllerHandler ->
        RtspServer(rtspRecordControllerHandler, 12345).start(andWait = true)
    }
}