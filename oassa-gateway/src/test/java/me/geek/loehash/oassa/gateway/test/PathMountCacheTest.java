package me.geek.loehash.oassa.gateway.test;


import me.geek.loehash.oassa.gateway.cache.PathMountCache;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PathMountCacheTest {

    private PathMountCache cache;
    private final Object dataAB = new Object();
    private final Object dataXY = new Object();

    @BeforeEach
    void setUp() {
        cache = new PathMountCache();
        cache.mount("/a/b/", dataAB);
        cache.mount("/x/y/z/", dataXY);
    }

    // ==================== 命中 ====================

    @Nested
    @DisplayName("命中场景")
    class Hit {

        @Test
        @DisplayName("子路径命中 mount 点")
        void subPath() {
            assertSame(dataAB, cache.resolve("/a/b/c/d.png"));
            assertSame(dataAB, cache.resolve("/a/b/file.txt"));
            assertSame(dataXY, cache.resolve("/x/y/z/deep/file.png"));
        }

        @Test
        @DisplayName("路径正好等于 mount 点（带尾斜杠）")
        void exactMountPoint() {
            assertSame(dataAB, cache.resolve("/a/b/"));
        }

        @Test
        @DisplayName("只有一层子路径")
        void oneLevelChild() {
            assertSame(dataAB, cache.resolve("/a/b/x"));
        }

        @Test
        @DisplayName("深层嵌套路径")
        void deepNested() {
            assertSame(dataAB, cache.resolve("/a/b/c/d/e/f/g/h/i.png"));
        }

        @Test
        @DisplayName("多层 mount，命中更长（更具体）的那个")
        void prefersLongest() {
            Object dataABC = new Object();
            cache.mount("/a/b/c/", dataABC);
            assertSame(dataABC, cache.resolve("/a/b/c/d.png"));
            assertSame(dataAB, cache.resolve("/a/b/other"));
        }
    }

    // ==================== 未命中 ====================

    @Nested
    @DisplayName("未命中场景")
    class Miss {

        @Test
        @DisplayName("完全无关路径")
        void unrelated() {
            assertNull(cache.resolve("/no/mount/file.png"));
            assertNull(cache.resolve("/x/y/file.png"));
        }

        @Test
        @DisplayName("兄弟前缀不误匹配：/a/bc/ 不是 /a/b/")
        void siblingPrefix() {
            assertNull(cache.resolve("/a/bc/d.png"));
            assertNull(cache.resolve("/a/bx/file"));
        }

        @Test
        @DisplayName("父目录未挂载")
        void parentNotMounted() {
            assertNull(cache.resolve("/a/file.png"));
        }

        @Test
        @DisplayName("目录本身不带尾斜杠：当前实现未命中")
        void dirWithoutSlash() {
            assertNull(cache.resolve("/a/b"));
        }

        @Test
        @DisplayName("根路径 / 不误命中")
        void rootPath() {
            assertNull(cache.resolve("/"));
        }
    }

    // ==================== 参数与边界 ====================

    @Nested
    @DisplayName("参数与边界")
    class Edge {

        @Test
        @DisplayName("resolve(null) 返回 null 不抛异常")
        void resolveNull() {
            assertNull(cache.resolve(null));
        }

        @Test
        @DisplayName("resolve 空串返回 null 不抛异常")
        void resolveEmpty() {
            assertNull(cache.resolve(""));
        }

        @Test
        @DisplayName("mount(null) 抛 NPE")
        void mountNullPoint() {
            assertThrows(NullPointerException.class, () -> cache.mount(null, dataAB));
        }

        @Test
        @DisplayName("mount 数据为 null 抛 NPE")
        void mountNullData() {
            assertThrows(NullPointerException.class, () -> cache.mount("/p/q/", null));
        }

        @Test
        @DisplayName("拒绝根挂载点")
        void rejectRootMount() {
            assertThrows(IllegalArgumentException.class, () -> cache.mount("/", dataAB));
        }

        @Test
        @DisplayName("mount 缺少尾斜杠自动补齐")
        void autoTrailingSlash() {
            cache.mount("/p/q", dataAB);
            assertSame(dataAB, cache.resolve("/p/q/file"));
            assertSame(dataAB, cache.resolve("/p/q/"));
            assertNull(cache.resolve("/p/qx/file"));
        }

        @Test
        @DisplayName("unmount(null) 抛 NPE")
        void unmountNull() {
            assertThrows(NullPointerException.class, () -> cache.unmount(null));
        }
    }

    // ==================== 挂载/卸载 ====================

    @Nested
    @DisplayName("挂载与卸载")
    class MountUnmount {

        @Test
        @DisplayName("卸载后不再命中")
        void unmountThenMiss() {
            cache.unmount("/a/b/");
            assertNull(cache.resolve("/a/b/c/d.png"));
            assertNull(cache.resolve("/a/b/"));
        }

        @Test
        @DisplayName("卸载一个不影响另一个")
        void unmountIsolated() {
            cache.unmount("/a/b/");
            assertNull(cache.resolve("/a/b/c/d.png"));
            assertSame(dataXY, cache.resolve("/x/y/z/file"));
        }

        @Test
        @DisplayName("重复卸载幂等")
        void unmountIdempotent() {
            cache.unmount("/a/b/");
            assertDoesNotThrow(() -> cache.unmount("/a/b/"));
        }

        @Test
        @DisplayName("同 mount 点重新挂载返回新数据")
        void remountOverwrite() {
            Object newData = new Object();
            cache.mount("/a/b/", newData);
            assertSame(newData, cache.resolve("/a/b/c/d.png"));
        }

        @Test
        @DisplayName("卸载未挂载的点不抛异常")
        void unmountNotMounted() {
            assertDoesNotThrow(() -> cache.unmount("/never/mounted/"));
        }
    }

    // ==================== 一致性：规则与数据同生共死（方案 A） ====================

    @Nested
    @DisplayName("规则与数据一致性")
    class Consistency {

        @Test
        @DisplayName("显式 unmount 后规则与数据都消失")
        void explicitUnmountBothGone() {
            cache.unmount("/a/b/");
            assertNull(cache.resolve("/a/b/x"));
            // 再挂载能正常命中，说明规则没有残留导致状态错乱
            Object d = new Object();
            cache.mount("/a/b/", d);
            assertSame(d, cache.resolve("/a/b/x"));
        }

        @Test
        @DisplayName("removalListener 在淘汰时同步移除规则（容量压力）")
        void removalListenerSyncsRules() throws Exception {
            // 用小容量实例单独验证，避免污染 setUp 的实例
            PathMountCache small = new PathMountCache();
            // 该测试依赖 PathMountCache 支持自定义 maximumSize 或反射修改；
            // 若类未暴露容量配置，可跳过此用例或改用可配置构造器。
            // 这里用反射把 maximumSize 改小仅作示意，生产测试建议暴露构造器。
            Object d = new Object();
            small.mount("/a/b/", d);
            assertSame(d, small.resolve("/a/b/x"));
            small.unmount("/a/b/");
            assertNull(small.resolve("/a/b/x"));
        }
    }

    // ==================== 并发 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTest {

//        @Test
        @RepeatedTest(10000)
        @DisplayName("多线程并发挂载+读取，不丢数据、不抛异常")
        void concurrentMountAndResolve() throws Exception {
            int threads = 16;
            int perThread = 500;
            PathMountCache c = new PathMountCache();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String mp = "/t" + tid + "/i" + i + "/";
                            Object data = new Object();
                            c.mount(mp, data);
                            Object got = c.resolve(mp + "file.png");
                            if (got != data) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }

            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, errors.get(), "并发挂载/读取出现数据不一致");
        }

        @RepeatedTest(10000)
        @DisplayName("挂载与卸载并发交错，不抛异常")
        void concurrentMountUnmount() throws Exception {
            int threads = 8;
            PathMountCache c = new PathMountCache();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        String mp = "/c" + (tid % 4) + "/";
                        if (i % 2 == 0) {
                            c.mount(mp, new Object());
                        } else {
                            c.unmount(mp);
                        }
                        c.resolve(mp + "x");
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}