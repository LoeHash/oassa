package me.geek.loehash.oassa.gateway.init;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.Getter;
import me.geek.loehash.oassa.gateway.handler.HttpServerHandler;
import me.geek.loehash.oassa.common.utils.ByteSizeUtil;
import me.geek.loehash.oassa.gateway.obj.AllObjects;

// create pipeline for every connection;
@Getter
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {


    private final EventExecutorGroup businessGroup;

    public HttpServerInitializer(EventExecutorGroup businessGroup) {
        this.businessGroup = businessGroup;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // 1. 编解码：字节 ↔ HttpRequest/HttpResponse
                .addLast(new HttpServerCodec())
                // 2. 聚合：分块 → FullHttpRequest（最大 64KB）
                .addLast(new HttpObjectAggregator(ByteSizeUtil.toBytes(64, ByteSizeUtil.KB)))
                // 3. 业务处理
                .addLast(businessGroup, new HttpServerHandler(AllObjects.directorClient));
    }

}
