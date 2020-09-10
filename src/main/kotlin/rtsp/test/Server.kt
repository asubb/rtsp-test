package rtsp.test

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
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
import java.util.concurrent.atomic.AtomicBoolean


var recording = AtomicBoolean(false)
val fos = FileOutputStream(File("/users/asubb/tmp/output.wav").also { it.createNewFile() })

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
                    response.headers().add(RtspHeaderNames.TRANSPORT, msg.headers()[RtspHeaderNames.TRANSPORT])
                }
                RtspMethods.RECORD -> {
                    recording.set(true)
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

class Receiver : ChannelInboundHandlerAdapter() {

    private val log = KotlinLogging.logger { }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        log.info { "Received $msg" }
        if (!recording.get()) {
            ctx.fireChannelRead(msg)
        } else if (msg is ByteBuf) {
            val buffer = ByteArrayOutputStream()
            msg.readBytes(buffer, msg.readableBytes())
            fos.write(buffer.toByteArray())
            fos.flush()
            msg.release()
            ctx.fireChannelReadComplete()
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