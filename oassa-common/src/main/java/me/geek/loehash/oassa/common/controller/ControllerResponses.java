package me.geek.loehash.oassa.common.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制器响应写入工具类。
 * 供 {@link ApiController} 子类及入口 handler 统一使用，保证响应格式一致。
 */
public final class ControllerResponses {

    private static final Logger log = LoggerFactory.getLogger(ControllerResponses.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ControllerResponses() {
    }

    public static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                                HttpResponseStatus status, Object body) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(body);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));

            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                    .set(HttpHeaderNames.CACHE_CONTROL, "no-store");

            writeResponse(ctx, req, response);
        } catch (Exception e) {
            log.error("Failed to write JSON response", e);
            ctx.close();
        }
    }

    public static void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                                 HttpResponseStatus status, int code, String message) {
        sendJson(ctx, req, status, DirectorResponse.error(code, message));
    }

    private static void writeResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                      FullHttpResponse response) {
        if (HttpUtil.isKeepAlive(req)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
