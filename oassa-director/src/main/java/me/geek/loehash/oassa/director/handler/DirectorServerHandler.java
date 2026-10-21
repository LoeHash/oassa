package me.geek.loehash.oassa.director.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import me.geek.loehash.oassa.common.protocol.DirectorStatus;
import me.geek.loehash.oassa.director.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class DirectorServerHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log =
            LoggerFactory.getLogger(DirectorServerHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOOKUP_PATH =
            "/internal/v1/routes/lookup";

    private final RouteService routeService;

    public DirectorServerHandler(RouteService routeService) {
        this.routeService = routeService;
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext ctx,
            FullHttpRequest req
    ) {
        if (!req.decoderResult().isSuccess()) {
            send(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorResponse.error(
                            DirectorStatus.INVALID_REQUEST,
                            "Invalid HTTP request"
                    ));
            return;
        }

        if (!HttpMethod.GET.equals(req.method())) {
            send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    DirectorResponse.error(
                            DirectorStatus.METHOD_NOT_ALLOWED,
                            "Only GET is supported"
                    ));
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        if (!LOOKUP_PATH.equals(decoder.path())) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND,
                    DirectorResponse.error(
                            DirectorStatus.ENDPOINT_NOT_FOUND,
                            "Endpoint not found"
                    ));
            return;
        }

        Map<String, List<String>> params = decoder.parameters();
        List<String> values = params.get("path");

        if (values == null || values.size() != 1) {
            send(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorResponse.error(
                            DirectorStatus.INVALID_PATH,
                            "Exactly one path parameter is required"
                    ));
            return;
        }

        String path = values.get(0);

        if (!isValidObjectPath(path)) {
            send(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorResponse.error(
                            DirectorStatus.INVALID_PATH,
                            "Invalid object path"
                    ));
            return;
        }

        // try the cache first.
        //,..

        try {
            RouteLookupResponse route = routeService.lookup(path);

            if (route == null) {
                send(ctx, req, HttpResponseStatus.NOT_FOUND,
                        DirectorResponse.error(
                                DirectorStatus.ROUTE_NOT_FOUND,
                                "No matching route"
                        ));
                return;
            }

            send(ctx, req, HttpResponseStatus.OK,
                    DirectorResponse.success(route));

        } catch (Exception e) {
            log.error("Route lookup failed, path={}", path, e);

            send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    DirectorResponse.error(
                            DirectorStatus.ROUTE_SERVICE_ERROR,
                            "Route lookup failed"
                    ));
        }
    }

    private boolean isValidObjectPath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && !path.contains("?")
                && !path.contains("#")
                && !path.contains("\0");
    }

    private void send(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            HttpResponseStatus status,
            Object body
    ) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(body);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    status,
                    Unpooled.wrappedBuffer(bytes)
            );

            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE,
                            "application/json; charset=utf-8")
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                    .set(HttpHeaderNames.CACHE_CONTROL, "no-store");

            if (HttpUtil.isKeepAlive(req)) {
                response.headers().set(
                        HttpHeaderNames.CONNECTION,
                        HttpHeaderNames.CONNECTION
                );
                ctx.writeAndFlush(response);
            } else {
                ctx.writeAndFlush(response)
                        .addListener(ChannelFutureListener.CLOSE);
            }

        } catch (Exception e) {
            log.error("Failed to write HTTP response", e);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(
            ChannelHandlerContext ctx,
            Throwable cause
    ) {
        log.error("Director HTTP handler exception", cause);
        ctx.close();
    }
}