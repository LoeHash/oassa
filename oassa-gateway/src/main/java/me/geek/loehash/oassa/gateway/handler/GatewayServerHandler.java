package me.geek.loehash.oassa.gateway.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.common.protocol.DirectorClient;
import me.geek.loehash.oassa.common.utils.HttpResponseFactory;
import me.geek.loehash.oassa.gateway.cache.PathMountCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final PathMountCache<RouteLookupResponse> pmc;

    private static final Logger log =
            LoggerFactory.getLogger(GatewayServerHandler.class);

    private final DirectorClient directorClient;

    public GatewayServerHandler(DirectorClient directorClient,
                                PathMountCache<RouteLookupResponse> pmc) {
        this.directorClient = directorClient;
        this.pmc = pmc;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {

        if (!req.decoderResult().isSuccess()) {
            writeResponse(ctx, req,
                    HttpResponseFactory.error(
                            HttpResponseStatus.BAD_REQUEST,
                            "Invalid HTTP request"));
            return;
        }

        String path = new QueryStringDecoder(req.uri()).path();

        if (!isValidPath(path)) {
            writeResponse(ctx, req,
                    HttpResponseFactory.error(
                            HttpResponseStatus.BAD_REQUEST,
                            "Invalid object path"));
            return;
        }

        // resolveAsync 自带 single-flight：缓存未命中时，同一 path 只有一个请求打到 director，
        // 其余并发请求复用同一个 future，避免缓存过期引发的惊群。
        pmc.resolveAsync(path, directorClient::lookup, RouteLookupResponse::mountPath)
                .whenComplete((route, error) -> {

                    if (!ctx.channel().isActive()) {
                        return;
                    }

                    ctx.executor().execute(() -> {

                        if (!ctx.channel().isActive()) {
                            return;
                        }

                        if (error != null) {
                            log.error("Director lookup failed, path={}", path, error);

                            writeResponse(ctx, req,
                                    HttpResponseFactory.error(
                                            HttpResponseStatus.BAD_GATEWAY,
                                            "Director lookup failed"));
                            return;
                        }

                        if (route == null) {
                            writeResponse(ctx, req,
                                    HttpResponseFactory.error(
                                            HttpResponseStatus.NOT_FOUND,
                                            "No matching route"));
                            return;
                        }

                        redirect(ctx, req, route);
                    });
                });
    }


    /**
     * 302 临时重定向到 Streamer。
     */
    private void redirect(ChannelHandlerContext ctx,
                          FullHttpRequest req,
                          RouteLookupResponse route) {

        String location = route.streamer() + req.uri();

        writeResponse(ctx, req,
                HttpResponseFactory.redirect(
                        location));
    }

    /**
     * 统一写响应，处理 Keep-Alive。
     */
    private void writeResponse(ChannelHandlerContext ctx,
                               FullHttpRequest req,
                               FullHttpResponse response) {

        if (HttpUtil.isKeepAlive(req)) {
            response.headers().set(
                    HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private boolean isValidPath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Gateway HTTP handler error", cause);
        ctx.close();
    }
}