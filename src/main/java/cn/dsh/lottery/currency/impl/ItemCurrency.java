package cn.dsh.lottery.currency.impl;

import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyType;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 物品货币：以原版物品（钻石、下界之星、自定义点券道具等）作为抽奖货币。
 * <p>
 * 判定时可忽略物品的 NBT/自定义模型数据，只比较材质（可在配置中开关）。
 */
public final class ItemCurrency implements Currency {

    private final String id;
    private final String displayName;
    private final Material material;
    private final boolean ignoreMeta;
    private final String unit;

    public ItemCurrency(String id, String displayName, Material material, boolean ignoreMeta, String unit) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.ignoreMeta = ignoreMeta;
        this.unit = unit;
    }

    public Material material() {
        return material;
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
        return CurrencyType.ITEM;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Player online = player.getPlayer();
        if (online == null) {
            return 0.0D;
        }
        return count(online.getInventory(), Integer.MAX_VALUE);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Player online = player.getPlayer();
        if (online == null) {
            return false;
        }
        int need = (int) Math.ceil(amount);
        if (need <= 0) {
            return true;
        }
        if (count(online.getInventory(), need) < need) {
            return false;
        }
        PlayerInventory inventory = online.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && need > 0; i++) {
            ItemStack stack = contents[i];
            if (!matches(stack)) {
                continue;
            }
            int take = Math.min(need, stack.getAmount());
            need -= take;
            if (stack.getAmount() == take) {
                inventory.setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(i, stack);
            }
        }
        online.updateInventory();
        return need <= 0;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Player online = player.getPlayer();
        if (online == null) {
            return false;
        }
        int give = (int) Math.ceil(amount);
        if (give <= 0) {
            return true;
        }
        int remaining = give;
        int max = Math.max(1, material.getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(remaining, max);
            ItemStack stack = new ItemStack(material, size);
            var leftover = online.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    online.getWorld().dropItemNaturally(online.getLocation(), drop);
                }
            }
            remaining -= size;
        }
        online.updateInventory();
        return true;
    }

    @Override
    public boolean isAvailable() {
        return material != null && material.isItem();
    }

    @Override
    public boolean supportsOfflineDeposit() {
        return false;
    }

    @Override
    public String unit() {
        return unit;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getType() != material) {
            return false;
        }
        return true;
    }

    private int count(PlayerInventory inventory, int limit) {
        if (inventory == null) {
            return 0;
        }
        ItemStack[] contents = inventory.getContents();
        if (contents == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : contents) {
            if (matches(stack)) {
                total += stack.getAmount();
                if (total >= limit) {
                    return total;
                }
            }
        }
        return total;
    }

    /** 用于提示语中的展示名：翻译成客户端语言后的物品名。 */
    public String itemName() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.Component.translatable(material.translationKey()));
    }
}
