package cn.dsh.lottery.currency.impl;

import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyType;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

/**
 * Vault 主经济货币。
 * <p>
 * 接入 Vault 后即可自动兼容 EssentialsX、CMI、XConomy、iConomy、CraftConomy 等
 * 所有实现了 Vault Economy 接口的经济插件。
 */
public final class VaultCurrency implements Currency {

    private final Economy economy;
    private final String id;
    private final String displayName;
    private final String unit;

    public VaultCurrency(Economy economy, String id, String displayName, String unit) {
        this.economy = economy;
        this.id = id;
        this.displayName = displayName;
        this.unit = unit;
    }

    public Economy economy() {
        return economy;
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
        return CurrencyType.VAULT;
    }

    @Override
    public double balance(OfflinePlayer player) {
        try {
            return economy.getBalance(player);
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        try {
            return economy.has(player, amount);
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
            return economy.withdrawPlayer(player, amount).transactionSuccess();
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
            return economy.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return economy.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 使用经济插件自身的格式化输出，例如 “$1,000.00”。 */
    public String format(double amount) {
        try {
            return economy.format(amount);
        } catch (Throwable t) {
            return String.valueOf(amount);
        }
    }

    public int fractionalDigits() {
        try {
            return economy.fractionalDigits();
        } catch (Throwable t) {
            return 2;
        }
    }

    @Override
    public String unit() {
        return unit;
    }
}
