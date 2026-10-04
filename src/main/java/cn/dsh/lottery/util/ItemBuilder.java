package cn.dsh.lottery.util;

import cn.dsh.lottery.config.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 物品构建工具：从配置生成带名字、描述、附魔、耐久、自定义模型数据的奖品物品。
 */
public final class ItemBuilder {

    private static final Random RANDOM = new Random();

    private ItemBuilder() {
    }

    /**
     * 构建奖品物品。
     *
     * @param material   材质
     * @param amount     数量
     * @param name       显示名（MiniMessage）
     * @param lore       描述行（MiniMessage）
     * @param enchants   附魔，格式 {@code sharpness:5} 或 {@code minecraft:sharpness:5}
     * @param modelData  自定义模型数据，可为 null
     * @param unbreakable 是否无法破坏
     * @param glow       是否强制发光
     * @param ctx        占位符上下文
     */
    public static ItemStack build(Material material,
                                  int amount,
                                  String name,
                                  List<String> lore,
                                  List<String> enchants,
                                  Integer modelData,
                                  boolean unbreakable,
                                  boolean glow,
                                  PlaceholderContext ctx) {
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize() * 64)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null && !name.isBlank()) {
            meta.displayName(Text.mm(name, ctx));
        }
        if (lore != null && !lore.isEmpty()) {
            meta.lore(Text.mmList(lore, ctx));
        }
        if (enchants != null) {
            for (String raw : enchants) {
                Enchantment enchantment = resolveEnchantment(raw);
                if (enchantment == null) {
                    continue;
                }
                int level = parseLevel(raw);
                meta.addEnchant(enchantment, level, true);
            }
        }
        if (modelData != null) {
            // Paper 26.2 已推荐 CustomModelDataComponent，这里保留整数写法以兼容旧资源包。
            meta.setCustomModelData(modelData);
        }
        if (unbreakable) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** 在物品上写入插件标记，便于统计与找回。 */
    public static ItemStack tag(ItemStack stack, String key, String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(new NamespacedKey("paperlottery", key), PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 读取随机数量：{@code min..max} 区间内取值。 */
    public static int randomAmount(int min, int max) {
        int low = Math.max(1, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        return low == high ? low : low + RANDOM.nextInt(high - low + 1);
    }

    private static int parseLevel(String raw) {
        int idx = raw.lastIndexOf(':');
        if (idx < 0 || idx == raw.length() - 1) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.substring(idx + 1).trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Enchantment resolveEnchantment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw;
        int idx = value.lastIndexOf(':');
        if (idx > 0) {
            value = value.substring(0, idx);
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        try {
            NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
            if (key != null) {
                Enchantment byKey = Registry.ENCHANTMENT.get(key);
                if (byKey != null) {
                    return byKey;
                }
            }
        } catch (Throwable ignored) {
            // 忽略非法附魔，交由下方 fallback 处理。
        }
        try {
            return Registry.ENCHANTMENT.match(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解析材质名，支持 {@code minecraft:diamond} 与 {@code DIAMOND}。 */
    public static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material != null) {
            return material;
        }
        return Material.matchMaterial("minecraft:" + raw.trim().toLowerCase(Locale.ROOT));
    }

    /** 批量合并同类物品，便于一次性发放。 */
    public static List<ItemStack> merge(List<ItemStack> items) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            boolean handled = false;
            for (ItemStack exist : merged) {
                if (exist.isSimilar(item)) {
                    exist.setAmount(exist.getAmount() + item.getAmount());
                    handled = true;
                    break;
                }
            }
            if (!handled) {
                merged.add(item.clone());
            }
        }
        return merged;
    }

    /** 生成物品的描述行，用于 Dialog 的 {@code item} 组件。 */
    public static List<String> describeItem(ItemStack stack, Messages messages, PlaceholderContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(messages.raw("dialog.item-amount").replace("%amount%", String.valueOf(stack.getAmount())));
        Map<Enchantment, Integer> enchantments = stack.getEnchantments();
        if (!enchantments.isEmpty()) {
            Enchantment first = enchantments.keySet().iterator().next();
            lines.add(messages.raw("dialog.item-enchant")
                    .replace("%enchant%", first.getKey().getKey())
                    .replace("%level%", String.valueOf(enchantments.get(first))));
        }
        return lines;
    }
}
