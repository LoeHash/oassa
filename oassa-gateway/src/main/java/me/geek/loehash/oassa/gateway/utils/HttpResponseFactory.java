package me.geek.loehash.oassa.gateway.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * HTTP 响应创建工厂。
 * 统一构造 FullHttpResponse，自动设置 Content-Type / Content-Length。
 */
public final class HttpResponseFactory {

    private HttpResponseFactory() {}

    /** 200 + 纯文本 */
    public static FullHttpResponse text(String body) {
        return text(HttpResponseStatus.OK, body);
    }

    /** 指定状态码 + 纯文本 */
    public static FullHttpResponse text(HttpResponseStatus status, String body) {
        return build(status, body, "text/plain; charset=UTF-8");
    }

    /** 200 + JSON */
    public static FullHttpResponse json(String jsonBody) {
        return json(HttpResponseStatus.OK, jsonBody);
    }

    /** 指定状态码 + JSON */
    public static FullHttpResponse json(HttpResponseStatus status, String jsonBody) {
        return build(status, jsonBody, "application/json; charset=UTF-8");
    }

    /** 200 + HTML */
    public static FullHttpResponse html(String htmlBody) {
        return build(HttpResponseStatus.OK, htmlBody, "text/html; charset=UTF-8");
    }

    /** 重定向（默认 302 临时） */
    public static FullHttpResponse redirect(String location) {
        return redirect(location, HttpResponseStatus.FOUND);
    }

    /** 重定向（指定 301/302/307 等） */
    public static FullHttpResponse redirect(String location, HttpResponseStatus status) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        resp.headers()
                .set(HttpHeaderNames.LOCATION, location)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return resp;
    }

    /** 无 body 的响应（如 204、304） */
    public static FullHttpResponse empty(HttpResponseStatus status) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return resp;
    }

    /** 错误响应：状态码 + 文本描述 */
    public static FullHttpResponse error(HttpResponseStatus status, String message) {
        return text(status, message);
    }

    /** 通用构造：状态码 + body + Content-Type */
    private static FullHttpResponse build(HttpResponseStatus status, String body, String contentType) {
        ByteBuf content = body == null
                ? Unpooled.EMPTY_BUFFER
                : Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);

        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);

        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return resp;
    }
}
