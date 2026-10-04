package cn.dsh.lottery.currency;

/** 货币实现类型。 */
public enum CurrencyType {

    /** Vault 主经济（默认账户）。 */
    VAULT("vault"),
    /** Vault 多货币账户（如 PlayerPoints、GemsEconomy 的多币种）。 */
    POINTS("vault-multi"),
    /** 原版经验等级。 */
    XP("xp"),
    /** 原版物品。 */
    ITEM("item");

    private final String label;

    CurrencyType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
