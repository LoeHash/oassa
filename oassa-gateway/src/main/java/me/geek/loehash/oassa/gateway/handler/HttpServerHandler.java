package me.geek.loehash.oassa.gateway.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import me.geek.loehash.oassa.gateway.utils.HttpResponseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpServerHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // 1. 读请求
//        String uri = req.uri();
//        HttpMethod method = req.method();
//        String body = req.content().toString(CharsetUtil.UTF_8);
//        log.info("收到请求: {} {}, 线程={}", req.method(), req.uri(), Thread.currentThread().getName());
//        log.debug("the body: {}", body);
        // 永久重定向
        FullHttpResponse redirect = HttpResponseFactory.redirect("http://www.bilibili.com");
        ctx.writeAndFlush(redirect);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}

