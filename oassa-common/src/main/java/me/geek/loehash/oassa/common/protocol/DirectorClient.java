package me.geek.loehash.oassa.common.protocol;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import me.geek.loehash.oassa.common.entity.protocol.BatchLookupRequest;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DirectorClient {


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String directorBaseUrl;
    private final OkHttpClient client;

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
                    int code = response.code();

                    // 路由不存在：director 返回 404，按 null 处理而不是异常，
                    // 这样网关能正确返回 404 给客户端，而不是误报 502。
                    if (code == 404) {
                        future.complete(null);
                        return;
                    }

                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new RuntimeException(
                                "Director returned HTTP " + code));
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

    /**
     * 批量查询路由，异步返回。
     * <p>
     * POST {@code /internal/v1/routes/batch-lookup}，请求体为 {@code {"paths":[...]}}。
     * 返回 map 中只包含命中的路径，未命中的路径不在结果中。
     *
     * @param paths 待查询的路径列表
     * @return path -> 路由结果 的 map（未命中的 path 不在 map 中）
     */
    public CompletableFuture<Map<String, RouteLookupResponse>> batchLookup(List<String> paths) {
        CompletableFuture<Map<String, RouteLookupResponse>> future = new CompletableFuture<>();

        try {
            BatchLookupRequest requestBody = new BatchLookupRequest(paths);
            String json = MAPPER.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(directorBaseUrl + "/internal/v1/routes/batch-lookup")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (ResponseBody body = response.body()) {
                        int code = response.code();

                        if (!response.isSuccessful()) {
                            future.completeExceptionally(new RuntimeException(
                                    "Director batch returned HTTP " + code));
                            return;
                        }

                        byte[] bytes = body != null ? body.bytes() : new byte[0];

                        DirectorResponse<?> envelope = MAPPER.readValue(bytes, DirectorResponse.class);

                        if (envelope.code() != 0) {
                            future.completeExceptionally(new RuntimeException(
                                    "Director error code: " + envelope.code()));
                            return;
                        }

                        Map<String, RouteLookupResponse> result = MAPPER.convertValue(
                                envelope.data(),
                                new TypeReference<Map<String, RouteLookupResponse>>() {});

                        future.complete(result != null ? result : new HashMap<>());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    public void shutdown() {
        // 清空连接池
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

}