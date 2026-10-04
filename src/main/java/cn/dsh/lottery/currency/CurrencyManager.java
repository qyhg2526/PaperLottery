package cn.dsh.lottery.currency;

import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.currency.impl.ExperienceCurrency;
import cn.dsh.lottery.currency.impl.ItemCurrency;
import cn.dsh.lottery.currency.impl.VaultCurrency;
import cn.dsh.lottery.currency.impl.VaultMultiCurrency;
import cn.dsh.lottery.util.ItemBuilder;
import cn.dsh.lottery.util.PlaceholderContext;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 货币管理器：负责从 config.yml 读取货币定义、接入 Vault，并按 ID 提供货币实例。
 * <p>
 * 内置货币 ID：
 * <ul>
 *     <li>{@code vault:default} —— Vault 主经济；</li>
 *     <li>{@code xp:levels} / {@code xp:points} —— 原版经验；</li>
 *     <li>{@code item:&lt;材质&gt;} —— 任意物品。</li>
 * </ul>
 * 管理员也可以在配置中自定义任意条目，例如 {@code vault:points}（PlayerPoints 多货币）。
 */
public final class CurrencyManager {

    private final Logger logger;
    private final Messages messages;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private Economy vaultEconomy;

    public CurrencyManager(Logger logger, Messages messages) {
        this.logger = logger;
        this.messages = messages;
    }

    /** 重新加载：先挂接 Vault，再读取配置中的货币条目。 */
    public void reload(ConfigurationSection section) {
        currencies.clear();
        vaultEconomy = hookVault();
        if (section == null || section.getKeys(false).isEmpty()) {
            registerDefaults();
        } else {
            for (String id : section.getKeys(false)) {
                ConfigurationSection node = section.getConfigurationSection(id);
                if (node == null) {
                    continue;
                }
                register(id, node);
            }
        }
        registerFallbacks();
    }

    private Economy hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            RegisteredServiceProvider<Economy> provider =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider == null) {
                return null;
            }
            Economy economy = provider.getProvider();
            if (economy != null && economy.isEnabled()) {
                logger.info("已接入 Vault 经济系统：" + economy.getName());
                return economy;
            }
        } catch (Throwable t) {
            logger.warning("接入 Vault 经济系统失败：" + t.getMessage());
        }
        return null;
    }

    private void registerDefaults() {
        if (vaultEconomy != null) {
            currencies.put("vault:default", new VaultCurrency(vaultEconomy, "vault:default", "<gold>金币", "金币"));
        }
        currencies.put("xp:levels", new ExperienceCurrency("xp:levels", "<green>经验等级", true, "级"));
        currencies.put("xp:points", new ExperienceCurrency("xp:points", "<green>经验点", false, "点"));
        currencies.put("item:diamond", new ItemCurrency("item:diamond", "<aqua>钻石", Material.DIAMOND, true, "颗"));
    }

    private void register(String id, ConfigurationSection node) {
        String type = node.getString("type", "vault").toLowerCase(Locale.ROOT);
        String display = node.getString("display-name", id);
        String unit = node.getString("unit", "");
        String key = id.toLowerCase(Locale.ROOT);
        switch (type) {
            case "vault" -> {
                if (vaultEconomy == null) {
                    logger.warning("货币 " + id + " 需要 Vault 经济系统，但当前未检测到可用的经济插件，已跳过。");
                    return;
                }
                currencies.put(key, new VaultCurrency(vaultEconomy, key, display, unit));
            }
            case "vault-multi", "points", "multi" -> {
                if (vaultEconomy == null) {
                    logger.warning("货币 " + id + " 需要 Vault 经济系统，但当前未检测到可用的经济插件，已跳过。");
                    return;
                }
                String account = node.getString("account", id);
                currencies.put(key, new VaultMultiCurrency(vaultEconomy, key, account, display, unit));
            }
            case "xp", "experience" -> currencies.put(key,
                    new ExperienceCurrency(key, display, node.getBoolean("levels", true), unit));
            case "item" -> {
                Material material = ItemBuilder.material(node.getString("material", id.replace("item:", "")));
                if (material == null || material.isAir()) {
                    logger.warning("货币 " + id + " 的物品材质无效，已跳过。");
                    return;
                }
                currencies.put(key, new ItemCurrency(key, display, material,
                        node.getBoolean("ignore-meta", true), unit));
            }
            default -> logger.warning("货币 " + id + " 的 type 未知：" + type + "，已跳过。");
        }
    }

    /** 保证 {@code vault:default} 始终可被引用（未配置时也能正常工作）。 */
    private void registerFallbacks() {
        if (vaultEconomy != null && !currencies.containsKey("vault:default")) {
            currencies.put("vault:default", new VaultCurrency(vaultEconomy, "vault:default", "<gold>金币", "金币"));
        }
    }

    public Currency get(String id) {
        if (id == null || id.isBlank()) {
            return currencies.get("vault:default");
        }
        Currency currency = currencies.get(id.toLowerCase(Locale.ROOT));
        if (currency != null) {
            return currency;
        }
        // 支持简写：vault -> vault:default
        return currencies.get(id.toLowerCase(Locale.ROOT) + ":default");
    }

    public Collection<Currency> all() {
        return currencies.values();
    }

    public int size() {
        return currencies.size();
    }

    public boolean hasVault() {
        return vaultEconomy != null;
    }

    public Economy vaultEconomy() {
        return vaultEconomy;
    }

    /** 展示用余额文本：Vault 使用经济插件格式化，其余使用数字 + 单位。 */
    public String format(Currency currency, OfflinePlayer player) {
        return format(currency, balanceOf(currency, player));
    }

    /**
     * 格式化货币数量。
     * <p>
     * 货币可能因为 Vault 未安装 / 配置写错而为 null，此时必须优雅降级，
     * 否则打开抽奖界面就会抛出空指针异常。
     */
    public String format(Currency currency, double amount) {
        if (currency == null) {
            return PlaceholderContext.number(amount);
        }
        if (currency instanceof VaultCurrency vault) {
            return vault.format(amount);
        }
        String unit = currency.unit() == null ? "" : currency.unit();
        return PlaceholderContext.number(amount) + (unit.isEmpty() ? "" : " " + unit);
    }

    /** 安全查询余额（货币为 null 时返回 0）。 */
    public double balanceOf(Currency currency, OfflinePlayer player) {
        if (currency == null || player == null) {
            return 0.0D;
        }
        try {
            return currency.balance(player);
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    /** 货币展示名（货币为 null 时回退到货币 ID）。 */
    public String displayName(Currency currency, String fallbackId) {
        if (currency == null) {
            return fallbackId == null ? "?" : fallbackId;
        }
        return cn.dsh.lottery.util.Text.plain(currency.displayName());
    }

    public Messages messages() {
        return messages;
    }
}
