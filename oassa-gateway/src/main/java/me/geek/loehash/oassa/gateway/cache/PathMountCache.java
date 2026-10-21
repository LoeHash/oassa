package me.geek.loehash.oassa.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class PathMountCache<T> {

    private final Set<String> mountPoints = ConcurrentHashMap.newKeySet();

    private final Cache<String, T> mountCache;

    /**
     * 正在进行中的请求（single-flight）。
     * key 为请求路径，value 为对应的异步结果。
     * 同一时刻同一 key 只会有一个 loader 被调用，其余请求复用同一个 future。
     */
    private final ConcurrentHashMap<String, CompletableFuture<T>> inFlight = new ConcurrentHashMap<>();

    /**
     * 从缓存值中提取 TTL（秒）的函数。为 null 时使用默认过期时间。
     */
    private final Function<T, Long> ttlExtractor;


    public PathMountCache() {
        this(10_000, 30, TimeUnit.MINUTES, null);
    }

    public PathMountCache(Function<T, Long> ttlExtractor) {
        this(10_000, 30, TimeUnit.MINUTES, ttlExtractor);
    }

    @SuppressWarnings("unchecked")
    PathMountCache(long maximumSize, long defaultExpireAfter, TimeUnit unit, Function<T, Long> ttlExtractor) {
        this.ttlExtractor = ttlExtractor;
        Caffeine<String, T> builder = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .recordStats()
                .removalListener((String key, T value, RemovalCause cause) -> {
                    if (key != null && cause != RemovalCause.REPLACED) {
                        mountPoints.remove(key);
                    }
                });

        if (ttlExtractor != null) {
            // 按条目 TTL 过期：优先使用 value 自带的 ttlSeconds，否则回退到默认过期时间
            builder.expireAfter(new Expiry<String, T>() {
                @Override
                public long expireAfterCreate(String key, T value, long currentTime) {
                    Long ttl = ttlExtractor.apply(value);
                    return (ttl != null && ttl > 0)
                            ? TimeUnit.SECONDS.toNanos(ttl)
                            : unit.toNanos(defaultExpireAfter);
                }

                @Override
                public long expireAfterUpdate(String key, T value, long currentTime, long currentDuration) {
                    return expireAfterCreate(key, value, currentTime);
                }

                @Override
                public long expireAfterRead(String key, T value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            });
        } else {
            builder.expireAfterWrite(defaultExpireAfter, unit);
        }

        this.mountCache = builder.build();
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

    /**
     * 异步解析路径，带有 single-flight（请求合并）语义。
     * <p>
     * 当缓存未命中时，以路径的<b>目录前缀</b>作为合并 key：
     * 同一目录下的请求（如 {@code /a/b/c/t1.png} 与 {@code /a/b/c/t2.png}）
     * 共享同一次 director 调用，因为它们必然命中同一个 mount point。
     * <p>
     * 不同目录的请求不合并——这是安全的，因为子目录可能存在更具体的 mount point
     * （指向不同 streamer）。一旦首个请求完成并挂载了 mount point，同挂载点下
     * 其他目录的后续请求会直接命中缓存。
     *
     * @param path         请求路径
     * @param loader       异步加载函数（如调用 director）
     * @param keyExtractor 从加载结果中提取缓存 key（mountPath）的函数
     * @return 异步结果；命中缓存时返回已完成的 future
     */
    public CompletableFuture<T> resolveAsync(
            String path,
            Function<String, CompletableFuture<T>> loader,
            Function<T, String> keyExtractor) {

        // 快速路径：缓存命中直接返回
        T cached = resolve(path);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }

        // single-flight 的 key 是目录前缀，而非完整 path。
        // 同一目录下的文件必然命中同一个 mount point，因此可以合并请求。
        String dirKey = directoryOf(path);

        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<T> winner = inFlight.compute(dirKey, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing; // 复用正在进行的请求
            }
            return future; // 我们成为 leader
        });

        if (winner != future) {
            // 已有正在进行的请求，直接复用
            return winner;
        }

        // 我们是 leader，执行加载（loader 仍接收完整 path，director 需据此做最长前缀匹配）
        loader.apply(path).whenComplete((result, error) -> {
            // 仅当 map 中仍是我们的 future 时才移除
            inFlight.remove(dirKey, future);

            if (error != null) {
                future.completeExceptionally(error);
                return;
            }

            // 按 mountPath 写入缓存，供后续同挂载点的请求命中
            if (result != null) {
                String key = keyExtractor.apply(result);
                if (key != null) {
                    try {
                        mount(key, result);
                    } catch (RuntimeException e) {
                        future.completeExceptionally(e);
                        return;
                    }
                }
            }

            future.complete(result);
        });

        return future;
    }

    /**
     * 提取路径的目录前缀（包含末尾斜杠）。
     * <ul>
     *   <li>{@code /a/b/c/t1.png} → {@code /a/b/c/}</li>
     *   <li>{@code /a/b/c/}       → {@code /a/b/c/}</li>
     *   <li>{@code /a/b/c}        → {@code /a/b/}</li>
     *   <li>{@code /}             → {@code /}</li>
     * </ul>
     */
    private static String directoryOf(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            return path;
        }
        return path.substring(0, idx + 1);
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