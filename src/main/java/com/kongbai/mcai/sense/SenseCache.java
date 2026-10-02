package com.kongbai.mcai.sense;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 感知缓存。
 *
 * <p>动机：全量扫描世界是最贵的操作（{@code scanRadius=32} 时是 65×65×33 ≈ 14 万格）。
 * 每 tick 扫一次会直接把帧率打到个位数，而且 AI 决策频率只有秒级，重复扫描纯属浪费。
 *
 * <p>分层策略：
 * <ul>
 *   <li><b>静态层（方块）</b>：按 chunk 缓存直方图，只有 chunk 的"修改计数"变化才重算。</li>
 *   <li><b>动态层（实体 / 玩家状态）</b>：不缓存，每次现取。实体位置和血量过期一帧就是错的，
 *       缓存它们正是"AI 打空气"这类低级错误的来源。</li>
 * </ul>
 */
public final class SenseCache {

    /** 单个 chunk 的方块直方图缓存项。 */
    public static final class ChunkEntry {
        /** chunk 内方块 id -> 数量（降序前 N）。 */
        public final Map<String, Integer> histogram;
        /** 采样时的区块修改计数，用于失效判定。 */
        public final long stamp;
        public final long createdAtMs;

        ChunkEntry(Map<String, Integer> histogram, long stamp) {
            this.histogram = histogram;
            this.stamp = stamp;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<Long, ChunkEntry> chunks = new ConcurrentHashMap<>();
    private final int maxChunks;

    /** 命中 / 未命中计数，用于 /health 之类的自检接口。 */
    private long hits;
    private long misses;

    public SenseCache(int maxChunks) {
        this.maxChunks = Math.max(64, maxChunks);
    }

    /**
     * 查缓存。
     *
     * @param key      由维度 + chunkX + chunkZ 编码的 key
     * @param stamp    当前区块修改计数；与缓存不一致视为失效
     * @return 命中返回缓存项，否则 null
     */
    public ChunkEntry get(long key, long stamp) {
        ChunkEntry e = chunks.get(key);
        if (e == null) {
            misses++;
            return null;
        }
        if (e.stamp != stamp) {
            // 区块被改过，立即失效
            chunks.remove(key, e);
            misses++;
            return null;
        }
        hits++;
        return e;
    }

    public void put(long key, long stamp, Map<String, Integer> histogram) {
        if (chunks.size() >= maxChunks) {
            evictOldest();
        }
        chunks.put(key, new ChunkEntry(histogram, stamp));
    }

    public void clear() {
        chunks.clear();
        hits = 0;
        misses = 0;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public int size() { return chunks.size(); }

    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /** 淘汰最老的 1/4，避免一次性全清造成下一轮全部 miss。 */
    private void evictOldest() {
        int n = Math.max(1, chunks.size() / 4);
        chunks.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().createdAtMs, b.getValue().createdAtMs))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(chunks::remove);
    }

    /**
     * 把直方图裁剪到前 N 项，其余合并为 "其他"。
     * 目的：给 AI 的是"这里主要是石头和泥土"，而不是 200 行噪音。
     */
    public static Map<String, Integer> topN(Map<String, Integer> src, int n) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        src.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        int total = src.values().stream().mapToInt(Integer::intValue).sum();
        int kept = out.values().stream().mapToInt(Integer::intValue).sum();
        if (total - kept > 0) {
            out.put("...other", total - kept);
        }
        return out;
    }
}
