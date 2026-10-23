package me.geek.loehash.oassa.director.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.geek.loehash.oassa.common.entity.RouteEntry;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.director.service.RouteService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class RouteServiceImpl implements RouteService {

    /** 路由表：mountPath -> RouteEntry */
    private final ConcurrentMap<String, RouteEntry> routes = new ConcurrentHashMap<>();

    /**
     * 本地查询缓存：path -> RouteLookupResponse。
     * 缓存时间拉长至 5 分钟，减少重复解析开销。
     * 当路由表变更（register/unregister）时会主动失效。
     */
    private final Cache<String, RouteLookupResponse> lookupCache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    public RouteServiceImpl() {
        routes.put("/a/b/", new RouteEntry("/a/b/", "http://streamer-a.example.com", 30));
        routes.put("/a/", new RouteEntry("/a/", "http://streamer-b.example.com", 30));
        routes.put("/a/b/c/", new RouteEntry("/a/b/c/", "http://streamer-c.example.com", 30));
    }

    @Override
    public void register(RouteEntry entry) {
        validate(entry);
        routes.put(entry.mountPath(), entry);
        // 路由表变更，清空缓存避免返回过期数据
        lookupCache.invalidateAll();
    }

    @Override
    public boolean unregister(String mountPath) {
        boolean removed = routes.remove(mountPath) != null;
        if (removed) {
            lookupCache.invalidateAll();
        }
        return removed;
    }

    /**
     * 查询匹配对象路径的最长挂载路径（带本地缓存）。
     */
    @Override
    public RouteLookupResponse lookup(String path) {
        if (!isValidObjectPath(path)) {
            return null;
        }
        // Caffeine.get：缓存未命中时才调用 resolve，保证并发安全且只解析一次
        return lookupCache.get(path, this::resolveUncached);
    }

    /**
     * 批量查询（带本地缓存）。
     */
    @Override
    public Map<String, RouteLookupResponse> batchLookup(List<String> paths) {
        Map<String, RouteLookupResponse> result = new HashMap<>(paths.size());
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            RouteLookupResponse route = lookup(path);
            if (route != null) {
                result.put(path, route);
            }
        }
        return result;
    }

    /**
     * 未命中缓存时的实际解析逻辑：线性扫描找到最长匹配的挂载点。
     */
    private RouteLookupResponse resolveUncached(String path) {
        RouteEntry best = null;
        int bestLength = -1;

        for (RouteEntry entry : routes.values()) {
            String mountPath = entry.mountPath();
            if (matches(mountPath, path) && mountPath.length() > bestLength) {
                best = entry;
                bestLength = mountPath.length();
            }
        }

        if (best == null) {
            return null;
        }

        return new RouteLookupResponse(
                best.streamer(),
                best.mountPath(),
                best.ttlSeconds()
        );
    }

    private boolean matches(String mountPath, String path) {
        if ("/".equals(mountPath)) {
            return path.startsWith("/");
        }

        if (mountPath.endsWith("/")) {
            return path.startsWith(mountPath);
        }

        return path.equals(mountPath)
                || path.startsWith(mountPath + "/");
    }

    private boolean isValidObjectPath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && !path.contains("?")
                && !path.contains("#")
                && !path.contains("\0");
    }

    private void validate(RouteEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }

        String mountPath = entry.mountPath();

        if (mountPath == null
                || !mountPath.startsWith("/")
                || !mountPath.endsWith("/")) {
            throw new IllegalArgumentException(
                    "mountPath must start and end with '/'"
            );
        }

        if (entry.streamer() == null || entry.streamer().isBlank()) {
            throw new IllegalArgumentException(
                    "streamer must not be blank"
            );
        }

        if (entry.ttlSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "ttlSeconds must be positive"
            );
        }
    }
}
