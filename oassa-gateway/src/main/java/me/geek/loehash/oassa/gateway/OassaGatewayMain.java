package me.geek.loehash.oassa.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.*;
import me.geek.loehash.oassa.gateway.init.HttpServerInitializer;

import java.util.concurrent.TimeUnit;

public class OassaGatewayMain {

    private static final int CPU = Runtime.getRuntime().availableProcessors();

    public static int bossThreadN = 1;
    public static int workerThreadN = CPU;

    public static int connectionQueueLength = 512;          // 连接排队队列长度
    public static int serverPort = 8081;

    public static String businessGroupName = "oassa-gateway";
    public static int businessGroupThreadN = CPU * 2;
    public static int businessGroupMaxTaskN = 10000;
    public static EventExecutorGroup businessGroup;

    public static void main(String[] args) throws InterruptedException {
        // boss：负责接收连接
        EventLoopGroup bossGroup = new NioEventLoopGroup(bossThreadN);
        // worker：负责处理已建立连接的读写
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreadN);

        businessGroup = new DefaultEventExecutorGroup(
                businessGroupThreadN,
                new DefaultThreadFactory(businessGroupName),
                businessGroupMaxTaskN,
                RejectedExecutionHandlers.reject()
        );

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)          // 使用 NIO
                    .option(ChannelOption.SO_BACKLOG, connectionQueueLength)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)  // TCP 保活
                    .childHandler(new HttpServerInitializer(businessGroup));     // 每个连接装流水线

            ChannelFuture future = bootstrap.bind(serverPort).sync();
            System.out.println("HTTP server started on http://localhost:"+ serverPort);

            // blocked func
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            businessGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }
}
