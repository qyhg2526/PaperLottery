package cn.dsh.lottery.currency.impl;

import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyType;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * 原版经验等级货币。
 * <p>
 * 作为零依赖的兜底货币，即使服务器没有安装任何经济插件，抽奖也能照常运作。
 */
public final class ExperienceCurrency implements Currency {

    private final String id;
    private final String displayName;
    private final boolean useLevels;
    private final String unit;

    public ExperienceCurrency(String id, String displayName, boolean useLevels, String unit) {
        this.id = id;
        this.displayName = displayName;
        this.useLevels = useLevels;
        this.unit = unit;
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
        return CurrencyType.XP;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Player online = player.getPlayer();
        if (online == null) {
            return 0.0D;
        }
        return useLevels ? online.getLevel() : online.getExp();
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Player online = player.getPlayer();
        if (online == null) {
            return false;
        }
        int cost = (int) Math.ceil(amount);
        if (useLevels) {
            if (online.getLevel() < cost) {
                return false;
            }
            online.setLevel(online.getLevel() - cost);
        } else {
            int total = totalExperience(online);
            if (total < cost) {
                return false;
            }
            applyExperience(online, total - cost);
        }
        return true;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Player online = player.getPlayer();
        if (online == null) {
            return false;
        }
        int gain = (int) Math.ceil(amount);
        if (useLevels) {
            online.setLevel(online.getLevel() + gain);
        } else {
            applyExperience(online, totalExperience(online) + gain);
        }
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsOfflineDeposit() {
        return false;
    }

    @Override
    public String unit() {
        return unit;
    }

    /** 计算玩家当前总经验点（跨等级精确值）。 */
    public static int totalExperience(Player player) {
        int level = player.getLevel();
        int total = Math.round(player.getExp() * expToNext(level));
        for (int i = 0; i < level; i++) {
            total += expToNext(i);
        }
        return total;
    }

    /** 按总经验点重置等级与进度。 */
    public static void applyExperience(Player player, int total) {
        int level = 0;
        int remaining = Math.max(0, total);
        while (remaining >= expToNext(level) && level < 100_000) {
            remaining -= expToNext(level);
            level++;
        }
        player.setLevel(level);
        int need = expToNext(level);
        player.setExp(need <= 0 ? 0.0F : Math.min(1.0F, remaining / (float) need));
    }

    /** 原版升级所需经验公式。 */
    public static int expToNext(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 2;
    }
}
