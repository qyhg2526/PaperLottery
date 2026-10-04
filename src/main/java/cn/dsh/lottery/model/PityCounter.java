package cn.dsh.lottery.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 卡池保底计数：记录玩家在某卡池的连续未命中次数与累计抽奖次数。
 * <p>
 * 该对象与 {@link cn.dsh.lottery.data.PlayerData} 中的计数保持同步，
 * 主要作为读取用途的轻量快照。
 */
public final class PityCounter {

    private final String pool;
    private final Map<String, Integer> counters;
    private long total;
    private long lastDraw;

    public PityCounter(String pool) {
        this(pool, new HashMap<>(), 0L, 0L);
    }

    public PityCounter(String pool, Map<String, Integer> counters, long total, long lastDraw) {
        this.pool = pool;
        this.counters = counters == null ? new HashMap<>() : counters;
        this.total = total;
        this.lastDraw = lastDraw;
    }

    public String pool() {
        return pool;
    }

    public Map<String, Integer> counters() {
        return counters;
    }

    public int count(String rarity) {
        return counters.getOrDefault(rarity, 0);
    }

    public void set(String rarity, int value) {
        counters.put(rarity, Math.max(0, value));
    }

    public void increment(String rarity) {
        set(rarity, count(rarity) + 1);
    }

    public long total() {
        return total;
    }

    public void increaseTotal(long amount) {
        this.total += amount;
    }

    public long lastDraw() {
        return lastDraw;
    }

    public void lastDraw(long timestamp) {
        this.lastDraw = timestamp;
    }
}
