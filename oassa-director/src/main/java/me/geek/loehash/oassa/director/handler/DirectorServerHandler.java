package me.geek.loehash.oassa.director.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import me.geek.loehash.oassa.common.controller.ApiController;
import me.geek.loehash.oassa.common.controller.ControllerDispatcher;
import me.geek.loehash.oassa.common.controller.ControllerResponses;
import me.geek.loehash.oassa.common.protocol.DirectorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Director 的 HTTP 入口 handler。
 * <p>
 * 只负责请求解码校验与分发，具体业务逻辑由各 {@link ApiController} 实现。
 * 新增接口时无需改动本类，只需编写 Controller 并注册到 {@link ControllerDispatcher}。
 */
public class DirectorServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(DirectorServerHandler.class);

    private final ControllerDispatcher dispatcher;

    public DirectorServerHandler(ControllerDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorStatus.INVALID_REQUEST, "Invalid HTTP request");
            return;
        }

        try {
            boolean handled = dispatcher.dispatch(ctx, req);
            if (!handled) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND,
                        DirectorStatus.ENDPOINT_NOT_FOUND, "Endpoint not found");
            }
        } catch (Exception e) {
            log.error("Unhandled exception in dispatcher", e);
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    DirectorStatus.INTERNAL_ERROR, "Internal server error");
        }
    }

    private void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                           HttpResponseStatus status, int code, String message) {
        ControllerResponses.sendError(ctx, req, status, code, message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Director HTTP handler exception", cause);
        ctx.close();
    }
}
