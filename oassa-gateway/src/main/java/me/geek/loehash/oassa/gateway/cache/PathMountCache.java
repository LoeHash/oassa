package me.geek.loehash.oassa.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PathMountCache<T> {

    private final Set<String> mountPoints = ConcurrentHashMap.newKeySet();

    private final Cache<String, T> mountCache;


    public PathMountCache() {
        this(10_000, 30, TimeUnit.MINUTES);
    }

    PathMountCache(long maximumSize, long expireAfter, TimeUnit unit) {
        this.mountCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(expireAfter, unit)
                .recordStats()
                .removalListener((String key, T value, RemovalCause cause) -> {
                    if (key != null && cause != RemovalCause.REPLACED) {
                        mountPoints.remove(key);
                    }
                })
                .build();
    }

    public void mount(String mountPoint, T data) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        Objects.requireNonNull(data, "data");
        String mp = ensureTrailingSlash(mountPoint);
        if ("/".equals(mp)) {
            throw new IllegalArgumentException("root mount point not supported");
        }
        // 先让数据可见，再让规则可见
        mountCache.put(mp, data);
        mountPoints.add(mp);
    }

    public void unmount(String mountPoint) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        String mp = ensureTrailingSlash(mountPoint);
        // 先断规则，再删数据，避免匹配到正在删除的数据
        mountPoints.remove(mp);
        mountCache.invalidate(mp);
    }

    /**
     * @return 命中的 mount 数据；未挂载返回 null
     */
    public T resolve(String path) {
        if (path == null) {
            return null;
        }
        String mp = findMountPoint(path);
        return mp == null ? null : mountCache.getIfPresent(mp);
    }

    private String findMountPoint(String path) {
        int idx = path.length();
        while ((idx = path.lastIndexOf('/', idx - 1)) > 0) {
            String prefix = path.substring(0, idx + 1);
            if (mountPoints.contains(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static String ensureTrailingSlash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }


    public void printCache() {
        System.out.println("===== PathMountCache 条目 =====");
        System.out.println("mountPoints 数量: " + mountPoints.size());

        var map = mountCache.asMap();
        System.out.println("mountCache 实际条目数: " + map.size());

        if (map.isEmpty()) {
            System.out.println("(缓存为空)");
            System.out.println("===============================");
            return;
        }

        int i = 0;
        for (Map.Entry<String, T> entry : map.entrySet()) {
            i++;
            String key = entry.getKey();
            T value = entry.getValue();
            boolean rulePresent = mountPoints.contains(key);
            System.out.printf("[%d] %s -> %s%n",
                    i,
                    key,
                    describeValue(value));
            if (!rulePresent) {
                System.out.println("    ⚠ 规则缺失：mountPoints 中没有该 key");
            }
        }

        // 反向检查：有规则但没有数据的 mount 点（不一致）
        for (String mp : mountPoints) {
            if (!map.containsKey(mp)) {
                System.out.println("[!] 规则存在但数据缺失: " + mp);
            }
        }

        System.out.println("===============================");
    }


    private static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String s = value.toString();
        return value.getClass().getSimpleName() + "(" + s + ")";
    }
}