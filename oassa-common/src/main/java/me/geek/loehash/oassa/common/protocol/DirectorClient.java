package me.geek.loehash.oassa.common.protocol;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.QueryStringEncoder;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;

import java.util.concurrent.CompletableFuture;

public class DirectorClient {


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NioEventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(2);
    private final String directorHost;
    private final int directorPort;

    public DirectorClient(
            String directorHost,
            int directorPort
    ) {
        this.directorHost = directorHost;
        this.directorPort = directorPort;
    }

    public CompletableFuture<RouteLookupResponse> lookup(String path) {
        CompletableFuture<RouteLookupResponse> future =
                new CompletableFuture<>();

        QueryStringEncoder encoder =
                new QueryStringEncoder("/internal/v1/routes/lookup");
        encoder.addParam("path", path);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                    @Override
                                    protected void channelRead0(
                                            ChannelHandlerContext ctx,
                                            FullHttpResponse response
                                    ) {
                                        try {
                                            if (response.status().code() != 200) {
                                                future.completeExceptionally(
                                                        new RuntimeException(
                                                                "Director returned HTTP "
                                                                        + response.status().code()
                                                        )
                                                );
                                                return;
                                            }

                                            DirectorResponse<?> envelope = MAPPER.readValue(
                                                    io.netty.buffer.ByteBufUtil.getBytes(response.content()),
                                                    DirectorResponse.class
                                            );

                                            if (envelope.code() != 0) {
                                                future.completeExceptionally(
                                                        new RuntimeException(
                                                                "Director error code: "
                                                                        + envelope.code()
                                                        )
                                                );
                                                return;
                                            }

                                            RouteLookupResponse route =
                                                    MAPPER.convertValue(
                                                            envelope.data(),
                                                            RouteLookupResponse.class
                                                    );

                                            future.complete(route);
                                        } catch (Exception e) {
                                            future.completeExceptionally(e);
                                        } finally {
                                            ctx.close();
                                        }
                                    }

                                    @Override
                                    public void exceptionCaught(
                                            ChannelHandlerContext ctx,
                                            Throwable cause
                                    ) {
                                        future.completeExceptionally(cause);
                                        ctx.close();
                                    }
                                });
                    }
                });

        bootstrap.connect(directorHost, directorPort).addListener(
                (ChannelFutureListener) connectFuture -> {
                    if (!connectFuture.isSuccess()) {
                        future.completeExceptionally(connectFuture.cause());
                        return;
                    }

                    Channel channel = connectFuture.channel();

                    FullHttpRequest request = new DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1,
                            HttpMethod.GET,
                            encoder.toString()
                    );

                    request.headers()
                            .set(HttpHeaderNames.HOST,
                                    directorHost + ":" + directorPort)
                            .set(HttpHeaderNames.ACCEPT, "application/json")
                            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

                    channel.writeAndFlush(request);
                });

        return future;
    }

    public void shutdown() {
        eventLoopGroup.shutdownGracefully();
    }
}