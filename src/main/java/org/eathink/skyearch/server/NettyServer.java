package org.eathink.skyearch.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.eathink.skyearch.config.Config;
import org.eathink.skyearch.http.HttpApiHandler;
import org.eathink.skyearch.http.HttpRouter;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Netty HTTP 服务器。
 * I/O 事件循环(boss/workers)只负责收发, 业务 handler 挂在独立线程组上执行阻塞的 Lucene 调用。
 */
public final class NettyServer {

    private static final Logger LOG = Logger.getLogger(NettyServer.class.getName());

    private final Config.Server config;
    private final HttpRouter router;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final EventExecutorGroup businessGroup =
            new DefaultEventExecutorGroup(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

    private Channel serverChannel;

    public NettyServer(Config.Server config, HttpRouter router) {
        this.config = config;
        this.router = router;
    }

    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpServerExpectContinueHandler(),
                                new HttpObjectAggregator(1024 * 1024));
                        // 业务 handler 挂到独立线程组, 阻塞的 Lucene 调用不占用 I/O 事件循环
                        ch.pipeline().addLast(businessGroup, new HttpApiHandler(router));
                    }
                });
        serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
        LOG.info("http server started on " + config.host() + ":" + port());
    }

    /** 实际监听端口(port=0 时随机分配, 测试用)。 */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 阻塞直到服务端 channel 关闭。 */
    public void awaitShutdown() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    /** 优雅关闭: 先停止接收新连接, 再释放 I/O 与业务线程组。 */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        businessGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        LOG.info("http server stopped");
    }
}
