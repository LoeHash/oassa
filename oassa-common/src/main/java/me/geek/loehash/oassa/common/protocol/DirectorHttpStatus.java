package me.geek.loehash.oassa.common.protocol;

import io.netty.handler.codec.http.HttpResponseStatus;

public final class DirectorHttpStatus {

    private DirectorHttpStatus() {}

    // 2xx：成功
    public static final HttpResponseStatus OK =
            HttpResponseStatus.OK;                         // 200

    // 4xx：客户端请求错误
    public static final HttpResponseStatus BAD_REQUEST =
            HttpResponseStatus.BAD_REQUEST;               // 400

    public static final HttpResponseStatus UNAUTHORIZED =
            HttpResponseStatus.UNAUTHORIZED;               // 401

    public static final HttpResponseStatus FORBIDDEN =
            HttpResponseStatus.FORBIDDEN;                  // 403

    public static final HttpResponseStatus NOT_FOUND =
            HttpResponseStatus.NOT_FOUND;                  // 404

    public static final HttpResponseStatus METHOD_NOT_ALLOWED =
            HttpResponseStatus.METHOD_NOT_ALLOWED;         // 405

    public static final HttpResponseStatus TOO_MANY_REQUESTS =
            HttpResponseStatus.valueOf(429);               // 429

    // 5xx：服务端错误
    public static final HttpResponseStatus INTERNAL_SERVER_ERROR =
            HttpResponseStatus.INTERNAL_SERVER_ERROR;      // 500

    public static final HttpResponseStatus BAD_GATEWAY =
            HttpResponseStatus.BAD_GATEWAY;                // 502

    public static final HttpResponseStatus SERVICE_UNAVAILABLE =
            HttpResponseStatus.SERVICE_UNAVAILABLE;        // 503

    public static final HttpResponseStatus GATEWAY_TIMEOUT =
            HttpResponseStatus.GATEWAY_TIMEOUT;            // 504
}