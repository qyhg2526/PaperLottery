package cn.dsh.lottery.data;

import cn.dsh.lottery.model.PullRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单名玩家的抽奖数据：保底计数、各卡池统计、每日限额计数与最近流水。
 */
public final class PlayerData {

    private final java.util.UUID uuid;
    private String name;

    /** 卡池 ID -> 该卡池累计抽奖次数。 */
    private final Map<String, Integer> totals = new LinkedHashMap<>();
    /** 卡池 ID -> 品质 ID -> 连续未命中次数（保底计数）。 */
    private final Map<String, Map<String, Integer>> counters = new LinkedHashMap<>();
    /** 卡池 ID -> 奖品 ID -> 累计获得次数。 */
    private final Map<String, Map<String, Integer>> poolPrizes = new LinkedHashMap<>();
    /** 奖品 ID -> 累计获得次数（跨卡池）。 */
    private final Map<String, Integer> prizeCounts = new LinkedHashMap<>();
    /** 日期 -> 奖品 ID -> 当日获得次数。 */
    private final Map<String, Map<String, Integer>> dailyPrizes = new LinkedHashMap<>();
    /** 最近抽奖流水（最新在前）。 */
    private final List<PullRecord> history = new ArrayList<>();

    public PlayerData(java.util.UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public java.util.UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    // ---------------------------------------------------------------- 统计

    public int total(String pool) {
        return totals.getOrDefault(pool, 0);
    }

    public void addTotal(String pool) {
        totals.merge(pool, 1, Integer::sum);
    }

    public void addTotal(String pool, int amount) {
        totals.merge(pool, amount, Integer::sum);
    }

    public Map<String, Integer> totals() {
        return totals;
    }

    // ---------------------------------------------------------------- 保底

    public int counter(String pool, String rarity) {
        Map<String, Integer> map = counters.get(pool);
        return map == null ? 0 : map.getOrDefault(rarity, 0);
    }

    public void setCounter(String pool, String rarity, int value) {
        counters.computeIfAbsent(pool, k -> new LinkedHashMap<>()).put(rarity, Math.max(0, value));
    }

    public void incrementCounter(String pool, String rarity) {
        setCounter(pool, rarity, counter(pool, rarity) + 1);
    }

    public Map<String, Integer> counters(String pool) {
        return counters.getOrDefault(pool, Map.of());
    }

    public Map<String, Map<String, Integer>> allCounters() {
        return counters;
    }

    // ---------------------------------------------------------------- 奖品计数

    public int prizeCount(String prizeId) {
        return prizeCounts.getOrDefault(prizeId, 0);
    }

    public int poolPrizeCount(String pool, String prizeId) {
        Map<String, Integer> map = poolPrizes.get(pool);
        return map == null ? 0 : map.getOrDefault(prizeId, 0);
    }

    public void addPrizeCount(String pool, String prizeId, int amount) {
        prizeCounts.merge(prizeId, amount, Integer::sum);
        poolPrizes.computeIfAbsent(pool, k -> new LinkedHashMap<>()).merge(prizeId, amount, Integer::sum);
    }

    /** 今日获得次数。 */
    public int dailyCount(String prizeId) {
        return dailyCount(LocalDate.now().toString(), prizeId);
    }

    public int dailyCount(String date, String prizeId) {
        Map<String, Integer> map = dailyPrizes.get(date);
        return map == null ? 0 : map.getOrDefault(prizeId, 0);
    }

    public void addDailyCount(String prizeId, int amount) {
        dailyPrizes.computeIfAbsent(LocalDate.now().toString(), k -> new LinkedHashMap<>())
                .merge(prizeId, amount, Integer::sum);
    }

    /** 清理过期日期数据，避免文件无限增长。 */
    public void pruneDaily(int keepDays) {
        if (dailyPrizes.size() <= keepDays) {
            return;
        }
        List<String> keys = new ArrayList<>(dailyPrizes.keySet());
        keys.sort(String::compareTo);
        int remove = keys.size() - keepDays;
        for (int i = 0; i < remove; i++) {
            dailyPrizes.remove(keys.get(i));
        }
    }

    // ---------------------------------------------------------------- 流水

    public void addHistory(PullRecord record, int limit) {
        history.add(0, record);
        while (history.size() > Math.max(1, limit)) {
            history.remove(history.size() - 1);
        }
    }

    public List<PullRecord> history() {
        return history;
    }

    /** 重置某卡池的保底与统计。 */
    public void resetPool(String pool) {
        counters.remove(pool);
        totals.remove(pool);
        poolPrizes.remove(pool);
    }

    /**
     * 清理某卡池中不属于任何保底规则的计数。
     * <p>
     * 旧版本会按「奖品品质」额外写入计数器，导致没有保底规则的品质也留下计数
     * （例如 common=178）。这些幽灵计数既污染数据文件，也让界面上的保底进度
     * 看起来与真实抽数对不上。这里在加载时按当前配置清理一遍。
     *
     * @param pool    卡池 ID
     * @param allowed 该卡池保底规则涉及的品质集合
     * @return 被清理掉的品质条目数
     */
    public int pruneCounters(String pool, java.util.Set<String> allowed) {
        Map<String, Integer> map = counters.get(pool);
        if (map == null || map.isEmpty()) {
            return 0;
        }
        int before = map.size();
        map.keySet().removeIf(rarity -> !allowed.contains(rarity));
        return before - map.size();
    }

    /** 重置全部数据。 */
    public void resetAll() {
        counters.clear();
        totals.clear();
        poolPrizes.clear();
        prizeCounts.clear();
        dailyPrizes.clear();
        history.clear();
    }

    public Map<String, Map<String, Integer>> poolPrizes() {
        return poolPrizes;
    }

    public Map<String, Integer> prizeCounts() {
        return prizeCounts;
    }

    public Map<String, Map<String, Integer>> dailyPrizes() {
        return dailyPrizes;
    }

    /** 生成用于 GUI 展示的统计数据快照。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new HashMap<>();
        map.put("uuid", uuid.toString());
        map.put("name", name);
        map.put("totals", new LinkedHashMap<>(totals));
        map.put("prizes", new LinkedHashMap<>(prizeCounts));
        return map;
    }
}
