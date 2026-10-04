package cn.dsh.lottery.currency;

import org.bukkit.OfflinePlayer;

/**
 * 货币抽象层。
 * <p>
 * 所有抽奖消耗都通过该接口完成，因此插件天然支持“多货币”：
 * <ul>
 *     <li>{@link CurrencyType#VAULT} —— 通过 Vault 接入任意经济插件（EssentialsX / CMI / PlayerPoints 等）；
 *     <li>{@link CurrencyType#XP} —— 原版经验等级；
 *     <li>{@link CurrencyType#POINTS} —— Vault 的多货币账户（PlayerPoints 等支持 {@code withdrawPlayer(player, world, amount)}）；
 *     <li>{@link CurrencyType#ITEM} —— 任意原版物品（钻石、点券道具等）。
 * </ul>
 */
public interface Currency {

    /** 配置中书写用的货币 ID，例如 {@code vault}、{@code vault:token}、{@code item:diamond}。 */
    String id();

    /** 展示名（可含 MiniMessage 标签）。 */
    String displayName();

    CurrencyType type();

    /**
     * 查询余额。
     *
     * @param player 目标玩家（允许为离线玩家，经济类货币支持离线查询）
     * @return 余额；不支持查询时返回 0
     */
    double balance(OfflinePlayer player);

    /** 是否拥有足够余额。 */
    default boolean has(OfflinePlayer player, double amount) {
        return balance(player) >= amount - 1.0E-6D;
    }

    /**
     * 扣除指定数量。
     *
     * @return 是否扣除成功
     */
    boolean withdraw(OfflinePlayer player, double amount);

    /**
     * 发放指定数量（用于“货币类奖品”）。
     *
     * @return 是否发放成功
     */
    boolean deposit(OfflinePlayer player, double amount);

    /** 该货币当前是否可用（例如 Vault 未安装 / 经济插件未启用时为 false）。 */
    boolean isAvailable();

    /** 该货币是否支持扣款（作为抽奖消耗时必须为 true）。 */
    default boolean supportsWithdraw() {
        return true;
    }

    /** 是否支持离线发放（用于离线补发）。 */
    default boolean supportsOfflineDeposit() {
        return true;
    }

    /** 货币单位的展示后缀，例如“金币”“点”。 */
    default String unit() {
        return "";
    }
}
