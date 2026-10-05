package cn.dsh.lottery.data;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.model.PullRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 数据存储：以 YAML 持久化玩家保底计数、统计与流水，并缓存全服奖品发放总量。
 */
public final class DataStore {

    /** 每日计数保留天数，避免 playerdata.yml 无限膨胀。 */
    private static final int DAILY_RETENTION_DAYS = 30;

    private final PaperLotteryPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /** 奖品 ID -> 全服累计发放次数，用于 {@code limit} 判定。 */
    private final Map<String, Integer> globalPrizeTotals = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private volatile long lastSave;
    /** 已提示过「卡池已从配置移除」的卡池 ID，避免重复刷屏。 */
    private final java.util.Set<String> warnedMissingPools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DataStore(PaperLotteryPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
    }

    public void load() {
        cache.clear();
        globalPrizeTotals.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection node = players.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            PlayerData data = new PlayerData(uuid, node.getString("name", key));
            ConfigurationSection totals = node.getConfigurationSection("totals");
            if (totals != null) {
                for (String pool : totals.getKeys(false)) {
                    data.totals().put(pool.toLowerCase(Locale.ROOT), totals.getInt(pool, 0));
                }
            }
            ConfigurationSection counters = node.getConfigurationSection("counters");
            if (counters != null) {
                for (String pool : counters.getKeys(false)) {
                    ConfigurationSection rarityNode = counters.getConfigurationSection(pool);
                    if (rarityNode == null) {
                        continue;
                    }
                    for (String rarity : rarityNode.getKeys(false)) {
                        data.setCounter(pool.toLowerCase(Locale.ROOT), rarity.toLowerCase(Locale.ROOT),
                                rarityNode.getInt(rarity, 0));
                    }
                }
            }
            ConfigurationSection prizes = node.getConfigurationSection("prizes");
            if (prizes != null) {
                for (String prizeId : prizes.getKeys(false)) {
                    int count = prizes.getInt(prizeId, 0);
                    data.prizeCounts().merge(prizeId.toLowerCase(Locale.ROOT), count, Integer::sum);
                    globalPrizeTotals.merge(prizeId.toLowerCase(Locale.ROOT), count, Integer::sum);
                }
            }
            ConfigurationSection poolPrizes = node.getConfigurationSection("pool-prizes");
            if (poolPrizes != null) {
                for (String pool : poolPrizes.getKeys(false)) {
                    ConfigurationSection prizeNode = poolPrizes.getConfigurationSection(pool);
                    if (prizeNode == null) {
                        continue;
                    }
                    Map<String, Integer> target = data.poolPrizes()
                            .computeIfAbsent(pool.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>());
                    for (String prizeId : prizeNode.getKeys(false)) {
                        target.put(prizeId.toLowerCase(Locale.ROOT), prizeNode.getInt(prizeId, 0));
                    }
                }
            }
            ConfigurationSection daily = node.getConfigurationSection("daily");
            if (daily != null) {
                for (String date : daily.getKeys(false)) {
                    ConfigurationSection prizeNode = daily.getConfigurationSection(date);
                    if (prizeNode == null) {
                        continue;
                    }
                    Map<String, Integer> target = data.dailyPrizes()
                            .computeIfAbsent(date, k -> new LinkedHashMap<>());
                    for (String prizeId : prizeNode.getKeys(false)) {
                        target.put(prizeId.toLowerCase(Locale.ROOT), prizeNode.getInt(prizeId, 0));
                    }
                }
            }
            // 每日抽奖次数：日期 -> 卡池 -> 次数
            ConfigurationSection draws = node.getConfigurationSection("daily-draws");
            if (draws != null) {
                for (String date : draws.getKeys(false)) {
                    ConfigurationSection poolNode = draws.getConfigurationSection(date);
                    if (poolNode == null) {
                        continue;
                    }
                    Map<String, Integer> target = data.dailyDraws()
                            .computeIfAbsent(date, k -> new LinkedHashMap<>());
                    for (String poolId : poolNode.getKeys(false)) {
                        target.put(poolId.toLowerCase(Locale.ROOT), poolNode.getInt(poolId, 0));
                    }
                }
            }
            for (Map<?, ?> raw : node.getMapList("history")) {
                try {
                    data.history().add(new PullRecord(
                            asLong(raw.get("time")),
                            string(raw.get("pool")),
                            (int) asLong(raw.get("amount")),
                            string(raw.get("currency")),
                            asDouble(raw.get("cost")),
                            string(raw.get("summary"))
                    ));
                } catch (Throwable ignored) {
                    // 单条流水损坏不影响整体加载
                }
            }
            data.pruneDaily(DAILY_RETENTION_DAYS);
            pruneGhostCounters(data);
            cache.put(uuid, data);
        }
        plugin.getLogger().info("已载入 " + cache.size() + " 名玩家的抽奖数据。");
    }

    /**
     * 清理旧版本遗留的幽灵保底计数。
     * <p>
     * 旧实现会按「奖品品质」写入计数器，于是没有配置保底规则的品质也会留下计数，
     * 界面上就会出现与实际抽数无关的数字。这里按当前配置把它们删掉，
     * 并只在真正清理到数据时输出一次汇总日志。
     */
    private void pruneGhostCounters(PlayerData data) {
        int removed = 0;
        for (String poolId : new java.util.ArrayList<>(data.allCounters().keySet())) {
            cn.dsh.lottery.model.Pool pool = plugin.lotteryConfig() == null
                    ? null : plugin.lotteryConfig().pools().get(poolId);
            if (pool == null) {
                if (warnedMissingPools.add(poolId)) {
                    plugin.getLogger().warning("玩家数据中存在已从配置移除的卡池 " + poolId
                            + "，其保底计数暂时保留（若确认不再使用可用 /lottery reset * " + poolId + " 清理）。");
                }
                continue;
            }
            java.util.Set<String> allowed = new java.util.HashSet<>();
            for (cn.dsh.lottery.model.Pool.PityRule rule : pool.pityRules()) {
                allowed.add(rule.rarity());
            }
            removed += data.pruneCounters(poolId, allowed);
        }
        if (removed > 0) {
            dirty = true;
            plugin.getLogger().info("已清理 " + removed + " 条无效的保底计数条目（旧版本遗留数据）。");
        }
    }

    /** 获取（必要时创建）玩家数据。 */
    public PlayerData get(UUID uuid, String name) {
        PlayerData data = cache.computeIfAbsent(uuid, id -> new PlayerData(id, name));
        data.name(name);
        return data;
    }

    public PlayerData getIfPresent(UUID uuid) {
        return cache.get(uuid);
    }

    /** 全部已缓存玩家数据（只读视图）。 */
    public java.util.Collection<PlayerData> all() {
        return java.util.Collections.unmodifiableCollection(cache.values());
    }

    /** 按玩家名查找（不区分大小写）。 */
    public PlayerData findByName(String name) {
        if (name == null) {
            return null;
        }
        for (PlayerData data : cache.values()) {
            if (name.equalsIgnoreCase(data.name())) {
                return data;
            }
        }
        return null;
    }

    /** 全服奖品发放总量。 */
    public int globalCount(String prizeId) {
        return globalPrizeTotals.getOrDefault(prizeId.toLowerCase(Locale.ROOT), 0);
    }

    public void addGlobalCount(String prizeId, int amount) {
        globalPrizeTotals.merge(prizeId.toLowerCase(Locale.ROOT), amount, Integer::sum);
        dirty = true;
    }

    public void markDirty() {
        dirty = true;
    }

    /** 立即保存（主线程调用，文件较小时开销可接受）。 */
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection players = yaml.createSection("players");
        for (PlayerData data : cache.values()) {
            ConfigurationSection node = players.createSection(data.uuid().toString());
            node.set("name", data.name());
            node.set("totals", new LinkedHashMap<>(data.totals()));
            ConfigurationSection counters = node.createSection("counters");
            data.allCounters().forEach((pool, map) -> {
                ConfigurationSection target = counters.createSection(pool);
                map.forEach((rarity, value) -> target.set(rarity, value));
            });
            node.set("prizes", new LinkedHashMap<>(data.prizeCounts()));
            ConfigurationSection poolPrizes = node.createSection("pool-prizes");
            data.poolPrizes().forEach((pool, map) -> {
                ConfigurationSection target = poolPrizes.createSection(pool);
                map.forEach((prizeId, value) -> target.set(prizeId, value));
            });
            ConfigurationSection daily = node.createSection("daily");
            data.dailyPrizes().forEach((date, map) -> {
                ConfigurationSection target = daily.createSection(date);
                map.forEach((prizeId, value) -> target.set(prizeId, value));
            });
            ConfigurationSection dailyDraws = node.createSection("daily-draws");
            data.dailyDraws().forEach((date, map) -> {
                ConfigurationSection target = dailyDraws.createSection(date);
                map.forEach((poolId, value) -> target.set(poolId, value));
            });
            node.set("history", data.history().stream().map(record -> Map.of(
                    "time", record.time(),
                    "pool", record.pool(),
                    "amount", record.amount(),
                    "currency", record.currency(),
                    "cost", record.cost(),
                    "summary", record.summary()
            )).toList());
        }
        ConfigurationSection globals = yaml.createSection("global");
        globals.set("prize-totals", new LinkedHashMap<>(globalPrizeTotals));
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录。");
            }
            yaml.save(file);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据失败", e);
        }
    }

    /** 定期保存：仅在数据发生变化时写盘。 */
    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void shutdown() {
        save();
    }

    public long lastSave() {
        return lastSave;
    }

    public int trackedPlayers() {
        return cache.size();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0D;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
