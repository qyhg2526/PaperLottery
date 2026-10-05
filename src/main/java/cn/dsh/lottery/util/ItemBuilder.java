package cn.dsh.lottery.util;

import cn.dsh.lottery.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
            // 附魔书（ENCHANTED_BOOK）的附魔必须写进「存储附魔」，
            // 否则只会得到一本「自身带附魔的书」，右键无法取出附魔。
            boolean storage = material == Material.ENCHANTED_BOOK
                    && meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta;
            for (String raw : enchants) {
                Enchantment enchantment = resolveEnchantment(raw);
                if (enchantment == null) {
                    continue;
                }
                int level = parseLevel(raw);
                if (storage) {
                    ((org.bukkit.inventory.meta.EnchantmentStorageMeta) meta).addStoredEnchant(enchantment, level, true);
                } else {
                    meta.addEnchant(enchantment, level, true);
                }
            }
            // 没有指定显示名时，自动按存储的附魔生成名字，
            // 否则客户端只会显示「附魔书」看不出内容。
            if (storage && (name == null || name.isBlank())) {
                Component auto = storedEnchantName(meta, ctx);
                if (auto != null) {
                    meta.displayName(auto);
                }
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

    /**
     * 按附魔书里存储的附魔自动生成显示名，例如「<gray>附魔书 <dark_gray>· <white>经验修补 I」。
     * <p>
     * 名字取自附魔的翻译键，交由客户端按玩家语言渲染，因此中英文客户端都能正确显示。
     *
     * @return 生成的显示名；没有存储附魔时返回 null
     */
    private static Component storedEnchantName(ItemMeta meta, PlaceholderContext ctx) {
        if (!(meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage)
                || !storage.hasStoredEnchants()) {
            return null;
        }
        Map.Entry<Enchantment, Integer> first = storage.getStoredEnchants().entrySet().iterator().next();
        // 附魔名走翻译键，等级用罗马数字；这样不依赖服务端语言文件
        Component enchantName = Component.translatable("enchantment."
                + first.getKey().getKey().getNamespace() + "." + first.getKey().getKey().getKey());
        return Component.text().append(Text.mm("<gray>附魔书 <dark_gray>· <white>"))
                .append(enchantName)
                .append(Component.text(" " + romanLevel(first.getValue())))
                .build();
    }

    /** 1~10 转罗马数字，超出范围直接返回阿拉伯数字。 */
    public static String romanLevel(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
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

    /**
     * 给玩家头颅写入所有者信息。
     * <p>
     * 优先级从高到低：
     * <ol>
     *   <li><b>skull-texture</b>（配置里直接写皮肤纹理值）—— 最可靠：
     *       不联网、不依赖服务器缓存，任何环境都能得到正确的皮肤，推荐使用；</li>
     *   <li><b>skull-uuid</b> —— 向服务端缓存查询皮肤，玩家从未上过线的服务器可能查不到；</li>
     *   <li><b>skull-owner</b> —— 仅按名字设置，交由服务端自行解析。</li>
     * </ol>
     * 纹理值可从 Mojang 会话服务器获取：
     * {@code https://sessionserver.mojang.com/session/minecraft/profile/<UUID>?unsigned=false}
     *
     * @param stack     目标物品（应为 PLAYER_HEAD）
     * @param owner     玩家名，可为 null
     * @param uuid      玩家 UUID 字符串（带或不带连字符均可），可为 null
     * @param texture   皮肤纹理 base64 值，可为 null
     * @param signature 皮肤纹理签名，可为 null（多数情况下不校验也可正常显示）
     * @param ctx       占位符上下文，用于支持 {@code %player%} 之类的动态名字
     */
    public static void applySkullOwner(ItemStack stack, String owner, String uuid,
                                       String texture, String signature, PlaceholderContext ctx) {
        if (stack == null || stack.getType() != Material.PLAYER_HEAD) {
            return;
        }
        ItemMeta rawMeta = stack.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return;
        }
        String name = PlaceholderContext.applyTo(ctx, owner);
        UUID id = parseUuid(uuid);
        String skinValue = texture == null ? null : texture.replaceAll("\\s", "");
        if (name != null && name.isBlank()) {
            name = null;
        }

        // 1) Paper API：可写入皮肤纹理，也可补全缓存
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile;
            if (id != null && name != null) {
                profile = Bukkit.createProfile(id, name);
            } else if (id != null) {
                profile = Bukkit.createProfile(id);
            } else {
                profile = Bukkit.createProfile(name);
            }
            if (skinValue != null && !skinValue.isEmpty()) {
                // 直接写入纹理属性：离线也有效
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty(
                        "textures", skinValue, signature));
            } else if (id == null) {
                // 没有纹理也没有 UUID 时，尝试从服务器缓存补齐
                profile.completeFromCache(true);
            }
            meta.setPlayerProfile(profile);
            stack.setItemMeta(meta);
            return;
        } catch (Throwable ignored) {
            // 继续尝试标准 API
        }

        // 2) 标准 API
        try {
            org.bukkit.profile.PlayerProfile profile = id != null
                    ? (name != null ? Bukkit.createPlayerProfile(id, name) : Bukkit.createPlayerProfile(id))
                    : Bukkit.createPlayerProfile(name);
            meta.setOwnerProfile(profile);
            stack.setItemMeta(meta);
            return;
        } catch (Throwable ignored) {
            // 继续尝试按名字设置
        }

        // 3) 兜底：按名字设置，由服务端自行解析
        try {
            @SuppressWarnings("deprecation")
            boolean ok = name != null && meta.setOwner(name);
            if (ok) {
                stack.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 无法设置所有者时保持原样，不影响抽奖流程
        }
    }

    /** 解析 UUID 字符串，支持带连字符与不带连字符两种写法。 */
    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replace("-", "");
        if (value.length() != 32) {
            return null;
        }
        try {
            return new UUID(
                    Long.parseUnsignedLong(value.substring(0, 16), 16),
                    Long.parseUnsignedLong(value.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
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
