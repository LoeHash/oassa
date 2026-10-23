package me.geek.loehash.oassa.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.*;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.common.protocol.DirectorClient;
import me.geek.loehash.oassa.gateway.cache.PathMountCache;
import me.geek.loehash.oassa.gateway.init.HttpServerInitializer;
import me.geek.loehash.oassa.gateway.obj.AllObjects;

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

    public static String directorAddress = "127.0.0.1";
    public static int directorIPort = 8082;

    /** 预刷新调度间隔（秒）。 */
    private static final long REFRESH_INTERVAL_SECONDS = 5;

    /**
     * 在 businessGroup 中启动预刷新定时任务。
     * <p>
     * 每隔 {@link #REFRESH_INTERVAL_SECONDS} 秒扫描一次缓存，将即将过期的条目
     * 批量发送给 director 刷新。任务运行在 businessGroup 线程中，
     * 与请求处理共用线程池，避免引入额外线程。
     */
    private static void startRefreshScheduler() {
        businessGroup.next().scheduleAtFixedRate(() -> {
            try {
                AllObjects.pmc.refreshExpiringEntries();
            } catch (Throwable t) {
                // 防止单次异常打断后续调度
                System.err.println("refreshExpiringEntries error: " + t.getMessage());
            }
        }, REFRESH_INTERVAL_SECONDS, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

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


        AllObjects.directorClient = new DirectorClient(directorAddress, directorIPort, true);
        // 使用 director 返回的 ttlSeconds 作为缓存过期时间；
        // keyExtractor 用于从结果中提取 mountPath 作为缓存 key；
        // batchLoader 用于过期前批量刷新，减少打到 director 的次数。
        AllObjects.pmc = new PathMountCache<>(
                RouteLookupResponse::ttlSeconds,
                RouteLookupResponse::mountPath,
                AllObjects.directorClient::batchLookup
        );

        // 在 businessGroup 中定时执行预刷新：扫描即将过期的缓存并批量向 director 续期。
        // 刷新期间旧值仍可用（stale-while-refresh），不阻塞请求。
        startRefreshScheduler();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)          // 使用 NIO
                    .option(ChannelOption.SO_BACKLOG, connectionQueueLength)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)  // TCP 保活
                    .childHandler(new HttpServerInitializer(businessGroup));     // 每个连接装流水线

            ChannelFuture future = bootstrap.bind(serverPort).sync();
            System.out.println("Oassa-Gateway server started on http://localhost:"+ serverPort);

            // blocked func
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            businessGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            AllObjects.directorClient.shutdown();
        }
    }
}
