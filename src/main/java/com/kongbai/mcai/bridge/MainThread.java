package com.kongbai.mcai.bridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 主线程调度。
 *
 * <p><b>为什么必须有它：</b> Minecraft 的绝大部分状态（世界、实体、背包、
 * 输入）都不是线程安全的，在别的线程上读写会导致随机崩溃、方块幽灵、
 * 实体丢失这类极难排查的问题。而我们的 HTTP 服务、AI 循环都跑在独立线程上。
 *
 * <p>所以：任何触碰游戏状态的操作，都要丢进这里，在客户端 tick 里执行。
 * 效果和 {@code Minecraft.execute(...)} 一样，但我们不依赖 Fabric API。
 */
public final class MainThread {

    private static final ConcurrentLinkedQueue<Task<?>> QUEUE = new ConcurrentLinkedQueue<>();

    private MainThread() {}

    /**
     * 提交一个需要在主线程执行的任务并等待结果。
     *
     * @param timeoutMs 超时（毫秒）；超时返回 {@code null}
     */
    public static <T> T run(Supplier<T> supplier, long timeoutMs) {
        if (Thread.currentThread().getName().equals("Render thread")
                || Thread.currentThread().getName().equals("Client thread")) {
            // 已经在主线程，直接跑，避免自己等自己造成死锁
            return supplier.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        QUEUE.add(new Task<>(supplier, f));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** 不需要返回值的提交。 */
    public static void run(Runnable r) {
        run(() -> { r.run(); return null; }, 5000L);
    }

    /** 由 tick 钩子调用，每 tick 排空队列。 */
    public static void drain() {
        Task<?> t;
        int guard = 0;
        while ((t = QUEUE.poll()) != null && guard++ < 64) {
            completeOne(t);
        }
    }

    /** 单独抽成泛型方法：通配符 Task<?> 无法直接 complete，需要捕获类型。 */
    private static <T> void completeOne(Task<T> t) {
        try {
            t.future().complete(t.supplier().get());
        } catch (Throwable e) {
            t.future().completeExceptionally(e);
        }
    }

    public static int pending() {
        return QUEUE.size();
    }

    private record Task<T>(Supplier<T> supplier, CompletableFuture<T> future) {}
}
