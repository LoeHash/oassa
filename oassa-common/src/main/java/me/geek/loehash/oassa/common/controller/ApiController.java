package me.geek.loehash.oassa.common.controller;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制器基类。子类通过 {@link #basePath()} 声明自己负责的 URL 前缀，
 * 在 {@link #handle} 中根据子路径分发到具体方法。
 * <p>
 * 这样新增接口只需新增一个 Controller 子类并注册到 {@link ControllerDispatcher}，
 * 无需改动核心 handler，便于后期扩展。
 */
public abstract class ApiController {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * @return 该控制器负责的 URL 前缀，例如 {@code /internal/v1/routes}
     */
    public abstract String basePath();

    /**
     * 处理请求。
     *
     * @param ctx     Netty 上下文
     * @param req     完整 HTTP 请求
     * @param subPath 去掉 basePath 后的剩余子路径
     */
    public abstract void handle(ChannelHandlerContext ctx, FullHttpRequest req, String subPath);

    /**
     * 以 JSON 格式发送响应。
     */
    protected void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                            HttpResponseStatus status, Object body) {
        ControllerResponses.sendJson(ctx, req, status, body);
    }

    /**
     * 发送业务错误响应（使用统一的 DirectorResponse 信封）。
     */
    protected void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                             HttpResponseStatus status, int code, String message) {
        ControllerResponses.sendError(ctx, req, status, code, message);
    }
}
