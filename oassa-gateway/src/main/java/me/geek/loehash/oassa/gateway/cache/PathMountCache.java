package me.geek.loehash.oassa.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 路径挂载缓存，支持：
 * <ul>
 *   <li><b>最长前缀匹配</b>：按挂载点（目录前缀）缓存数据。</li>
 *   <li><b>Single-flight 请求合并</b>：缓存未命中时，同一目录的并发请求合并为一次 director 调用。</li>
 *   <li><b>Stale-while-refresh</b>：条目即将过期前（默认提前 60s）由后台批量刷新，
 *       刷新期间旧值仍可对外提供服务，不阻塞请求。</li>
 *   <li><b>按条目 TTL 过期</b>：从 value 中提取 ttlSeconds 计算过期时间。</li>
 * </ul>
 *
 * @param <T> 缓存值类型
 */
public class PathMountCache<T> {

    /** 默认 TTL（秒）：ttlExtractor 为 null 时使用。 */
    private static final long DEFAULT_TTL_SECONDS = 30 * 60;

    /** 预刷新提前量（秒）：在过期前这么久就开始刷新。 */
    private static final long REFRESH_LEAD_SECONDS = 60;

    /**
     * 过期后仍可服务旧值的宽限期（秒）。
     * 超过此时间仍未刷新成功的条目会被视为失效并触发重新加载。
     */
    private static final long STALE_GRACE_SECONDS = 60;

    /**
     * 缓存条目包装：除了值本身，还记录过期时间与刷新元数据。
     */
    static final class Entry<T> {
        volatile T value;
        /** 绝对过期时间（epoch millis）。 */
        volatile long expireAt;
        /** 应开始刷新的时间（epoch millis）= expireAt - refreshLead。 */
        volatile long refreshAt;
        /** 是否正在刷新中（防止重复刷新）。 */
        final AtomicBoolean refreshing = new AtomicBoolean(false);
    }

    private final Set<String> mountPoints = ConcurrentHashMap.newKeySet();

    private final Cache<String, Entry<T>> mountCache;

    /** 正在进行中的请求（single-flight），key 为目录前缀。 */
    private final ConcurrentHashMap<String, CompletableFuture<T>> inFlight = new ConcurrentHashMap<>();

    /** 从 value 提取 TTL（秒）的函数。为 null 时使用默认 TTL。 */
    private final Function<T, Long> ttlExtractor;

    /** 从 value 提取缓存 key（mountPath）的函数。 */
    private final Function<T, String> keyExtractor;

    /** 批量加载函数：传入路径列表，返回 path -> value 的 map。 */
    private final Function<List<String>, CompletableFuture<Map<String, T>>> batchLoader;

    private final long refreshLeadMillis;
    private final long staleGraceMillis;


    public PathMountCache() {
        this(10_000, null, null, null);
    }

    public PathMountCache(Function<T, Long> ttlExtractor) {
        this(10_000, ttlExtractor, null, null);
    }

    public PathMountCache(
            Function<T, Long> ttlExtractor,
            Function<T, String> keyExtractor,
            Function<List<String>, CompletableFuture<Map<String, T>>> batchLoader
    ) {
        this(10_000, ttlExtractor, keyExtractor, batchLoader);
    }

    PathMountCache(
            long maximumSize,
            Function<T, Long> ttlExtractor,
            Function<T, String> keyExtractor,
            Function<List<String>, CompletableFuture<Map<String, T>>> batchLoader
    ) {
        this.ttlExtractor = ttlExtractor;
        this.keyExtractor = keyExtractor;
        this.batchLoader = batchLoader;
        this.refreshLeadMillis = TimeUnit.SECONDS.toMillis(REFRESH_LEAD_SECONDS);
        this.staleGraceMillis = TimeUnit.SECONDS.toMillis(STALE_GRACE_SECONDS);

        Caffeine<String, Entry<T>> builder = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .recordStats()
                .removalListener((String key, Entry<T> value, RemovalCause cause) -> {
                    if (key != null && cause != RemovalCause.REPLACED) {
                        mountPoints.remove(key);
                    }
                });

        // 过期完全由 Entry.expireAt 自行管理，Caffeine 不做时间淘汰，
        // 仅靠 maximumSize 做容量兜底。
        this.mountCache = builder.build();
    }

    public void mount(String mountPoint, T data) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        Objects.requireNonNull(data, "data");
        String mp = ensureTrailingSlash(mountPoint);
        if ("/".equals(mp)) {
            throw new IllegalArgumentException("root mount point not supported");
        }

        Entry<T> entry = new Entry<>();
        entry.value = data;
        long ttl = extractTtl(data);
        long now = System.currentTimeMillis();
        entry.expireAt = now + TimeUnit.SECONDS.toMillis(ttl);
        entry.refreshAt = entry.expireAt - refreshLeadMillis;

        // 先让数据可见，再让规则可见
        mountCache.put(mp, entry);
        mountPoints.add(mp);
    }

    public void unmount(String mountPoint) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        String mp = ensureTrailingSlash(mountPoint);
        // 先断规则，再删数据，避免匹配到正在删除的数据
        mountPoints.remove(mp);
        mountCache.invalidate(mp);
    }

    // ==================== 读取 ====================

    /**
     * @return 命中的 mount 数据；未挂载或已过宽限期返回 null
     */
    public T resolve(String path) {
        if (path == null) {
            return null;
        }
        String mp = findMountPoint(path);
        if (mp == null) {
            return null;
        }
        Entry<T> entry = mountCache.getIfPresent(mp);
        if (entry == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        // 超过过期时间 + 宽限期仍未刷新成功，视为彻底失效，触发重新加载
        if (now > entry.expireAt + staleGraceMillis) {
            return null;
        }

        // 在过期前、或过期后的宽限期内，都返回值（stale-while-refresh）
        return entry.value;
    }

    /**
     * 异步解析路径，带有 single-flight（请求合并）语义。
     * <p>
     * 当缓存未命中时，以路径的<b>目录前缀</b>作为合并 key：
     * 同一目录下的请求共享同一次 director 调用。
     *
     * @param path   请求路径
     * @param loader 异步加载函数（如调用 director.lookup）
     * @return 异步结果；命中缓存时返回已完成的 future
     */
    public CompletableFuture<T> resolveAsync(
            String path,
            Function<String, CompletableFuture<T>> loader) {

        // 快速路径：缓存命中直接返回（含 stale-while-refresh 的旧值）
        T cached = resolve(path);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }

        // single-flight 的 key 是目录前缀
        String dirKey = directoryOf(path);

        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<T> winner = inFlight.compute(dirKey, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing; // 复用正在进行的请求
            }
            return future; // 我们成为 leader
        });

        if (winner != future) {
            return winner;
        }

        // leader 执行加载
        loader.apply(path).whenComplete((result, error) -> {
            inFlight.remove(dirKey, future);

            if (error != null) {
                future.completeExceptionally(error);
                return;
            }

            if (result != null && keyExtractor != null) {
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
     * 扫描所有条目，将即将过期（已过 refreshAt）的条目收集起来，
     * 一次性调用 {@link #batchLoader} 批量刷新。
     * <p>
     * 刷新期间旧值仍对外可用（stale-while-refresh）。
     * 刷新失败时保留旧值，等待下一个周期重试。
     * <p>
     * 本方法应定时调用（例如由 Netty businessGroup 定时调度）。
     */
    public void refreshExpiringEntries() {
        if (batchLoader == null || keyExtractor == null) {
            return;
        }

        long now = System.currentTimeMillis();
        List<String> toRefresh = new ArrayList<>();

        for (Map.Entry<String, Entry<T>> e : mountCache.asMap().entrySet()) {
            Entry<T> entry = e.getValue();
            // 已过刷新时间且未在刷新中 → 加入批量
            if (now >= entry.refreshAt && entry.refreshing.compareAndSet(false, true)) {
                toRefresh.add(e.getKey());
            }
        }

        if (toRefresh.isEmpty()) {
            return;
        }

        List<String> keys = toRefresh;
        batchLoader.apply(keys).whenComplete((result, error) -> {
            if (error != null) {
                // 刷新失败：解除 refreshing 标记，保留旧值继续服务，下个周期重试
                for (String key : keys) {
                    Entry<T> e = mountCache.getIfPresent(key);
                    if (e != null) {
                        e.refreshing.set(false);
                    }
                }
                return;
            }

            for (String key : keys) {
                Entry<T> e = mountCache.getIfPresent(key);
                if (e == null) {
                    continue;
                }

                T value = result != null ? result.get(key) : null;
                if (value == null) {
                    // 路由已被 director 移除
                    unmount(key);
                    continue;
                }

                String newMountPath = keyExtractor.apply(value);
                // 如果挂载点发生变化（原挂载点被移除，命中了父级），重新挂载
                if (newMountPath != null && !key.equals(newMountPath)) {
                    unmount(key);
                    try {
                        mount(newMountPath, value);
                    } catch (RuntimeException ignored) {
                        // mount 失败则忽略，该路径下次请求会重新加载
                    }
                    continue;
                }

                // 原地刷新：先更新 refreshAt 避免被重复选中，再更新值与过期时间
                long ttl = extractTtl(value);
                long refreshAt = now + TimeUnit.SECONDS.toMillis(ttl) - refreshLeadMillis;
                e.refreshAt = refreshAt;
                e.value = value;
                e.expireAt = now + TimeUnit.SECONDS.toMillis(ttl);
                e.refreshing.set(false);
            }
        });
    }


    private long extractTtl(T value) {
        if (ttlExtractor == null) {
            return DEFAULT_TTL_SECONDS;
        }
        Long ttl = ttlExtractor.apply(value);
        return (ttl != null && ttl > 0) ? ttl : DEFAULT_TTL_SECONDS;
    }

    /**
     * 提取路径的目录前缀（包含末尾斜杠）。
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

        long now = System.currentTimeMillis();
        int i = 0;
        for (Map.Entry<String, Entry<T>> entry : map.entrySet()) {
            i++;
            String key = entry.getKey();
            Entry<T> e = entry.getValue();
            boolean rulePresent = mountPoints.contains(key);
            long ttlSec = (e.expireAt - now) / 1000;
            System.out.printf("[%d] %s -> %s (剩余TTL=%ds, refreshing=%s)%n",
                    i, key, describeValue(e.value), ttlSec, e.refreshing.get());
            if (!rulePresent) {
                System.out.println("    规则缺失：mountPoints 中没有该 key");
            }
        }

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
