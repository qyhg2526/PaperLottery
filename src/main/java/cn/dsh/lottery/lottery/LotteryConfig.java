package cn.dsh.lottery.lottery;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.config.ConditionSet;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.config.Rarity;
import cn.dsh.lottery.config.Settings;
import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyManager;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置加载器：解析 config.yml 中的品质、货币与卡池定义。
 */
public final class LotteryConfig {

    private final PaperLotteryPlugin plugin;
    private Settings settings = Settings.defaults("default");
    private Messages messages;
    private CurrencyManager currencyManager;
    private final Map<String, Pool> pools = new LinkedHashMap<>();
    /** 实际生效的奖励配置文件（rewards.yml 或回退到 config.yml）。 */
    private String rewardsSource = "rewards.yml";

    public LotteryConfig(PaperLotteryPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重新加载全部配置。 */
    public void load() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // 奖励单独放一个文件，方便单独编辑与备份
        FileConfiguration rewards = loadRewardsFile();
        if (rewards == null) {
            rewards = config;
            rewardsSource = "config.yml";
        }

        loadRarities(config);
        messages = Messages.load(config.getConfigurationSection("messages"));
        settings = Settings.load(config, firstConfiguredPool(rewards));

        currencyManager = new CurrencyManager(plugin.getLogger(), messages);
        ConfigurationSection currencyRoot = rewards.getConfigurationSection("currencies");
        if (currencyRoot == null) {
            currencyRoot = config.getConfigurationSection("currencies");
        }
        currencyManager.reload(currencyRoot);

        pools.clear();
        ConfigurationSection poolRoot = rewards.getConfigurationSection("pools");
        if (poolRoot != null) {
            for (String id : poolRoot.getKeys(false)) {
                ConfigurationSection section = poolRoot.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                Pool pool = loadPool(id, section);
                if (pool == null) {
                    plugin.getLogger().warning("卡池 " + id + " 解析失败，已跳过。");
                    continue;
                }
                pools.put(id.toLowerCase(Locale.ROOT), pool);
            }
        }
        plugin.getLogger().info("已载入 " + pools.size() + " 个卡池（来源 " + rewardsSource + "），货币 "
                + currencyManager.size() + " 种。");
    }

    /**
     * 读取 rewards.yml；文件不存在时尝试从 jar 内释放模板。
     * 返回 null 表示应回退到 config.yml 中的 pools 节点（兼容旧版本配置）。
     */
    private FileConfiguration loadRewardsFile() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            // 兼容：老配置把卡池写在 config.yml 里
            if (plugin.getConfig().isConfigurationSection("pools")) {
                plugin.getLogger().warning("未找到 rewards.yml，已改用 config.yml 中的 pools 节点；"
                        + "建议把卡池迁移到 rewards.yml 以便单独维护。");
                return null;
            }
            try {
                plugin.saveResource("rewards.yml", false);
            } catch (Throwable t) {
                plugin.getLogger().warning("无法生成 rewards.yml：" + t.getMessage());
                return null;
            }
        }
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            plugin.getLogger().severe("rewards.yml 解析失败：" + e.getMessage());
            return null;
        }
        rewardsSource = "rewards.yml";
        return yaml;
    }

    /** 奖励配置文件的实际来源，用于 /lottery info。 */
    public String rewardsSource() {
        return rewardsSource;
    }

    private String firstConfiguredPool(FileConfiguration config) {
        ConfigurationSection poolRoot = config.getConfigurationSection("pools");
        if (poolRoot == null || poolRoot.getKeys(false).isEmpty()) {
            return "normal";
        }
        return poolRoot.getKeys(false).iterator().next();
    }

    private void loadRarities(FileConfiguration config) {
        Rarity.clear();
        ConfigurationSection section = config.getConfigurationSection("rarities");
        if (section == null || section.getKeys(false).isEmpty()) {
            // 兜底默认品质
            Rarity.register("common", "<gray>普通", 1000, "");
            Rarity.register("uncommon", "<green>优秀", 600, "");
            Rarity.register("rare", "<blue>稀有", 250, "");
            Rarity.register("epic", "<light_purple>史诗", 60, "");
            Rarity.register("legendary", "<gold>传说", 10, "%prefix%<gold>恭喜 %player% 抽到了 %prize_name%！");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection rarity = section.getConfigurationSection(id);
            if (rarity != null) {
                Rarity.register(id,
                        rarity.getString("display-name", id),
                        rarity.getInt("weight", 100),
                        rarity.getString("broadcast", ""));
            } else {
                // 支持 "legendary: 10" 这种简写
                Rarity.register(id, id, section.getInt(id, 100), "");
            }
        }
    }

    private Pool loadPool(String id, ConfigurationSection section) {
        String currencyId = section.getString("currency", "vault:default");
        Map<Integer, Double> costs = new LinkedHashMap<>();
        ConfigurationSection costSection = section.getConfigurationSection("cost");
        double defaultCost = 100.0D;
        if (costSection != null) {
            for (String key : costSection.getKeys(false)) {
                double value = costSection.getDouble(key, 0.0D);
                if ("default".equalsIgnoreCase(key)) {
                    defaultCost = value;
                    continue;
                }
                try {
                    costs.put(Integer.parseInt(key), value);
                } catch (NumberFormatException ignored) {
                    // 非数字 key 忽略
                }
            }
        } else {
            defaultCost = section.getDouble("cost", 100.0D);
        }

        List<Integer> amounts = new ArrayList<>(section.getIntegerList("amounts"));
        if (amounts.isEmpty()) {
            amounts.addAll(settings.amountOptions());
        }

        List<Pool.PityRule> pityRules = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("pity")) {
            Object rarity = raw.get("rarity");
            Object threshold = raw.get("threshold");
            if (rarity == null || threshold == null) {
                continue;
            }
            pityRules.add(new Pool.PityRule(String.valueOf(rarity).toLowerCase(Locale.ROOT),
                    parseInt(threshold, 90),
                    !Boolean.FALSE.equals(raw.get("reset-on-hit"))));
        }

        List<Prize> prizes = loadPrizes(id, section.getConfigurationSection("prizes"));

        return new Pool(
                id.toLowerCase(Locale.ROOT),
                section.getString("display-name", id),
                section.getStringList("description"),
                ItemBuilder.material(section.getString("icon", "CHEST")) == null
                        ? Material.CHEST
                        : ItemBuilder.material(section.getString("icon", "CHEST")),
                currencyId,
                costs,
                defaultCost,
                amounts,
                section.getString("permission", ""),
                ConditionSet.load(section.getConfigurationSection("conditions")),
                section.getDouble("bonus-weight", 1.0D),
                section.getStringList("announce-rarities"),
                emptyToNull(section.getString("guarantee-rarity", "")),
                emptyToNull(section.getString("ten-pull-rarity", "")),
                Math.max(1, section.getInt("guarantee-count", 1)),
                pityRules,
                // 每名玩家每日抽奖次数上限；-1 或不写表示不限
                section.getInt("daily-draw-limit", -1),
                prizes
        );
    }

    private List<Prize> loadPrizes(String poolId, ConfigurationSection section) {
        List<Prize> prizes = new ArrayList<>();
        if (section == null) {
            return prizes;
        }
        for (String prizeId : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(prizeId);
            if (node == null) {
                continue;
            }
            Prize.ItemSpec item = loadItem(node.getConfigurationSection("item"));
            Prize.CurrencyReward reward = null;
            ConfigurationSection currency = node.getConfigurationSection("currency");
            if (currency != null) {
                reward = new Prize.CurrencyReward(
                        currency.getString("id", "vault:default"),
                        currency.getDouble("min", currency.getDouble("amount", 0.0D)),
                        currency.getDouble("max", currency.getDouble("amount", 0.0D))
                );
            }
            prizes.add(new Prize(
                    prizeId.toLowerCase(Locale.ROOT),
                    node.getString("display-name", prizeId),
                    node.getString("rarity", "common"),
                    node.getDouble("weight", 100.0D),
                    ConditionSet.load(node.getConfigurationSection("conditions")),
                    item,
                    reward,
                    node.getStringList("commands"),
                    node.getDouble("bonus-weight", 0.0D),
                    node.getInt("limit", -1),
                    node.getInt("player-limit", -1),
                    node.getInt("daily-limit", -1)
            ));
        }
        if (prizes.isEmpty()) {
            plugin.getLogger().warning("卡池 " + poolId + " 没有任何奖品，玩家将无法抽奖。");
        }
        return prizes;
    }

    private Prize.ItemSpec loadItem(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        Material material = ItemBuilder.material(section.getString("material", ""));
        if (material == null) {
            plugin.getLogger().warning("奖品物品材质无效：" + section.getString("material"));
            return null;
        }
        int min = section.getInt("amount-min", section.getInt("amount", 1));
        int max = section.getInt("amount-max", section.getInt("amount", min));
        String skullOwner = section.getString("skull-owner", "");
        String skullUuid = section.getString("skull-uuid", "");
        // 纹理值很长，YAML 折行会引入空白与换行，这里统一去掉
        String skullTexture = section.getString("skull-texture", "").replaceAll("\\s", "");
        String skullSignature = section.getString("skull-signature", "").replaceAll("\\s", "");
        if (material == Material.PLAYER_HEAD) {
            if (skullTexture.isEmpty() && skullOwner.isBlank() && skullUuid.isBlank()) {
                plugin.getLogger().warning("奖品 " + section.getCurrentPath()
                        + " 使用 PLAYER_HEAD 但未配置 skull-texture / skull-uuid / skull-owner，"
                        + "将得到无皮肤的空白头颅。");
            } else if (skullTexture.isEmpty() && !skullOwner.isBlank()) {
                plugin.getLogger().info("奖品 " + section.getCurrentPath()
                        + " 未直接指定皮肤纹理，将尝试通过玩家缓存解析；"
                        + "若玩家从未在本服上线，建议改用 skull-texture 直接写入纹理。");
            }
        }
        return new Prize.ItemSpec(
                material,
                Math.max(1, min),
                Math.max(1, Math.max(min, max)),
                section.getString("name", ""),
                section.getStringList("lore"),
                section.getStringList("enchants"),
                section.isSet("model-data") ? section.getInt("model-data") : null,
                section.getBoolean("unbreakable", false),
                section.getBoolean("glow", false),
                skullOwner.isBlank() ? null : skullOwner,
                skullUuid.isBlank() ? null : skullUuid,
                skullTexture.isEmpty() ? null : skullTexture,
                skullSignature.isEmpty() ? null : skullSignature
        );
    }

    private static int parseInt(Object value, int def) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public CurrencyManager currencies() {
        return currencyManager;
    }

    public Map<String, Pool> pools() {
        return pools;
    }

    public Pool pool(String id) {
        if (id == null) {
            return null;
        }
        Pool pool = pools.get(id.toLowerCase(Locale.ROOT));
        if (pool != null) {
            return pool;
        }
        return pools.get(settings.defaultPool().toLowerCase(Locale.ROOT));
    }

    public Pool defaultPool() {
        return pools.get(settings.defaultPool().toLowerCase(Locale.ROOT));
    }

    /** 第一个“该玩家可用”的卡池，用于默认打开。 */
    public Pool firstUsable(org.bukkit.entity.Player player) {
        Pool preferred = defaultPool();
        if (preferred != null && preferred.isUsable(player)) {
            return preferred;
        }
        for (Pool pool : pools.values()) {
            if (pool.isUsable(player)) {
                return pool;
            }
        }
        return preferred != null ? preferred : pools.values().stream().findFirst().orElse(null);
    }

    public Currency currency(Pool pool) {
        return currencyManager.get(pool.currencyId());
    }
}
