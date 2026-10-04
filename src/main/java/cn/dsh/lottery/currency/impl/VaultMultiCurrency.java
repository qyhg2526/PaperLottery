package cn.dsh.lottery.currency.impl;

import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyType;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

/**
 * Vault 多货币账户。
 * <p>
 * 通过 {@link Economy#getBalance(OfflinePlayer, String)} / {@link Economy#withdrawPlayer(OfflinePlayer, String, double)}
 * 访问具名货币账户，可对接：
 * <ul>
 *     <li>PlayerPoints（账户名 {@code points}）</li>
 *     <li>GemsEconomy / MultiCurrency 等经济的多币种账户</li>
 *     <li>部分经济插件提供的“点券 / 代币”账户</li>
 * </ul>
 * 若底层经济插件不支持多货币，插件会在启动时给出提示并跳过该货币。
 */
public final class VaultMultiCurrency implements Currency {

    private final Economy economy;
    private final String id;
    private final String account;
    private final String displayName;
    private final String unit;

    public VaultMultiCurrency(Economy economy, String id, String account, String displayName, String unit) {
        this.economy = economy;
        this.id = id;
        this.account = account;
        this.displayName = displayName;
        this.unit = unit;
    }

    public String account() {
        return account;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public CurrencyType type() {
        return CurrencyType.POINTS;
    }

    @Override
    public double balance(OfflinePlayer player) {
        try {
            return economy.getBalance(player, account);
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        try {
            return economy.has(player, account, amount);
        } catch (Throwable t) {
            return balance(player) >= amount - 1.0E-6D;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        try {
            return economy.withdrawPlayer(player, account, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        try {
            return economy.depositPlayer(player, account, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        if (!economy.isEnabled()) {
            return false;
        }
        // 用在线玩家做一次真实查询，探测该具名账户是否被底层经济插件支持。
        // 若服务器当前无人在线，则暂时按“可用”处理，待有玩家时再验证。
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            try {
                economy.getBalance(online, account);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String unit() {
        return unit;
    }
}
