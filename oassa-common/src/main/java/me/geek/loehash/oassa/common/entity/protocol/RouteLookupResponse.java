package me.geek.loehash.oassa.common.entity.protocol;

/**
 * 统一路由寻找响应对象
 * @param streamer
 * @param mountPath
 * @param ttlSeconds
 */
public record RouteLookupResponse(
        String streamer,
        String mountPath,
        long ttlSeconds
) {
}