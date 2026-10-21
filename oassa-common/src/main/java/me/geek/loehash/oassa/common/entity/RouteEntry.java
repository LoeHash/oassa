package me.geek.loehash.oassa.common.entity;

public record RouteEntry(
        String mountPath,
        String streamer,
        long ttlSeconds
) {
}
