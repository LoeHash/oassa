package me.geek.loehash.oassa.director.service.impl;

import me.geek.loehash.oassa.common.entity.RouteEntry;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.director.service.RouteService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RouteServiceImpl implements RouteService {
    // 路径核心
    private final ConcurrentMap<String, RouteEntry> routes = new ConcurrentHashMap<>();

    public RouteServiceImpl() {
        routes.put("/a/b/", new RouteEntry("/a/b/", "http://streamer-a.example.com", 30));
        routes.put("/a/", new RouteEntry("/a/", "http://streamer-b.example.com", 30));
        routes.put("/a/b/c/", new RouteEntry("/a/b/c/", "http://streamer-c.example.com", 30));
    }

    public void register(RouteEntry entry) {
        validate(entry);
        routes.put(entry.mountPath(), entry);
    }

    public boolean unregister(String mountPath) {
        return routes.remove(mountPath) != null;
    }

    /**
     * 查询匹配对象路径的最长挂载路径。
     */
    public RouteLookupResponse lookup(String path) {
        if (!isValidObjectPath(path)) {
            return null;
        }

        RouteEntry best = null;
        int bestLength = -1;
//        System.out.println("finding...");
        for (RouteEntry entry : routes.values()) {
            String mountPath = entry.mountPath();

            if (matches(mountPath, path)
                    && mountPath.length() > bestLength) {
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
