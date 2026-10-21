package me.geek.loehash.oassa.common.protocol;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.*;
import lombok.Getter;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import okhttp3.*;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DirectorClient {


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String directorBaseUrl;
    private final OkHttpClient client;

    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(2);

    @Getter
    private final String directorHost;
    @Getter
    private final int directorPort;
    @Getter
    private final boolean isCache;

    public DirectorClient(
            String directorHost,
            int directorPort,
            boolean isCache
    ) {
        this.directorHost = directorHost;
        this.directorPort = directorPort;
        this.isCache = isCache;
        this.directorBaseUrl = "http://" + directorHost + ":" + directorPort;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(16, 5, TimeUnit.MINUTES)) // 连接池复用
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 查询路由，异步返回。
     * 回调线程是 OkHttp Dispatcher 线程，调用方拿到 future 自行决定消费线程。
     */
    public CompletableFuture<RouteLookupResponse> lookup(String path) {
        CompletableFuture<RouteLookupResponse> future = new CompletableFuture<>();

        HttpUrl url = HttpUrl.parse(directorBaseUrl + "/internal/v1/routes/lookup")
                .newBuilder()
                .addQueryParameter("path", path)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new RuntimeException(
                                "Director returned HTTP " + response.code()));
                        return;
                    }

                    byte[] bytes = body != null ? body.bytes() : new byte[0];

                    // 1. 解析外层信封
                    DirectorResponse<?> envelope = MAPPER.readValue(bytes, DirectorResponse.class);

                    // 2. 业务错误码
                    if (envelope.code() != 0) {
                        future.completeExceptionally(new RuntimeException(
                                "Director error code: " + envelope.code()));
                        return;
                    }

                    // 3. 把 data 转成具体的路由响应
                    RouteLookupResponse route = MAPPER.convertValue(
                            envelope.data(), RouteLookupResponse.class);

                    future.complete(route);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    public void shutdown() {
        // 清空连接池
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

}