package cn.dsh.lottery.config;

import cn.dsh.lottery.util.PapiHook;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 消息与 UI 文案（config.yml 的 {@code messages} 节点）。
 * <p>
 * 所有文案支持：
 * <ul>
 *     <li>MiniMessage 标签（如 {@code <gradient:gold:yellow>}）；</li>
 *     <li>旧版颜色代码（{@code &6}、{@code &l}）；</li>
 *     <li>内置占位符（见 {@link PlaceholderContext}）；</li>
 *     <li>PlaceholderAPI 变量（安装后自动解析）。</li>
 * </ul>
 * 若某个 key 在配置中不存在，会回退到代码内置的默认文案，避免出现空白提示。
 */
public final class Messages {

    /** 当前生效实例，供工具类读取品质名等静态信息。 */
    private static volatile Messages active;

    /** 消息路径常量，避免散落的字符串字面量。 */
    public static final class K {
        public static final String PLAYER_ONLY = "command.player-only";
        public static final String NO_PERMISSION = "command.no-permission";
        public static final String UNKNOWN_SUB = "command.unknown-subcommand";
        public static final String RELOADED = "command.reloaded";
        public static final String USAGE = "command.usage";
        public static final String UNKNOWN_POOL = "draw.unknown-pool";
        public static final String POOL_LOCKED = "draw.pool-locked";
        public static final String POOL_EMPTY = "draw.pool-empty";
        public static final String CURRENCY_MISSING = "draw.currency-missing";
        public static final String COOLDOWN = "draw.cooldown";
        public static final String BUSY = "draw.busy";
        public static final String INSUFFICIENT = "draw.insufficient";
        public static final String CHARGE_FAILED = "draw.charge-failed";
        public static final String DRAW_FAILED = "draw.failed";
        public static final String DRAW_SUCCESS = "draw.success";
        public static final String DRAW_EMPTY = "draw.empty";
        public static final String DRAW_LIMITED = "draw.limited";
        public static final String BAG_FULL = "draw.bag-full";
        public static final String PITY_TRIGGERED = "draw.pity-triggered";
        public static final String LUCKY = "draw.lucky";
        public static final String GIVE_SELF = "admin.give-self";
        public static final String GIVE_OTHER = "admin.give-other";
        public static final String GIVE_RECEIVED = "admin.give-received";
        public static final String GIVE_FAILED = "admin.give-failed";
        public static final String PLAYER_NOT_FOUND = "admin.player-not-found";
        public static final String RESET_DONE = "admin.reset-done";
        public static final String NO_POOLS = "admin.no-pools";
        public static final String BROADCAST = "broadcast.message";
        public static final String BROADCAST_LUCKY = "broadcast.lucky";
        public static final String DIALOG_CLOSE = "dialog.close";
        public static final String DIALOG_BACK = "dialog.back";
        public static final String DIALOG_AMOUNT_LABEL = "dialog.amount-label";
        public static final String DIALOG_POOL_LABEL = "dialog.pool-label";
    }

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, List<String>> lists = new LinkedHashMap<>();
    private final String prefixRaw;

    private Messages(ConfigurationSection section) {
        this.prefixRaw = section == null
                ? "<gray>[<gold>抽奖<gray>] <reset>"
                : section.getString("prefix", "<gray>[<gold>抽奖<gray>] <reset>");
        if (section != null) {
            flatten(section, "");
        }
        active = this;
    }

    public static Messages load(ConfigurationSection section) {
        return new Messages(section);
    }

    public static Messages active() {
        return active;
    }

    public static String rarityName(String rarity) {
        Rarity info = Rarity.get(rarity);
        return info == null ? rarity : info.displayName();
    }

    public static String rarityColor(String rarity) {
        Rarity info = Rarity.get(rarity);
        return info == null ? "<white>" : info.color();
    }

    private void flatten(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                ConfigurationSection child = section.getConfigurationSection(key);
                if (child != null) {
                    flatten(child, path);
                }
                continue;
            }
            if (section.isList(key)) {
                lists.put(path, List.copyOf(section.getStringList(key)));
                continue;
            }
            String value = section.getString(key);
            if (value != null) {
                values.put(path, value);
            }
        }
    }

    /** 原始文本（未做占位符替换）；缺失时回退到内置默认文案。 */
    public String raw(String path) {
        String value = values.get(path);
        if (value != null) {
            return value;
        }
        return Defaults.TEXT.getOrDefault(path, "");
    }

    public String raw(String path, String def) {
        String value = raw(path);
        return value.isEmpty() ? def : value;
    }

    public int intValue(String path, int def) {
        String value = raw(path);
        try {
            return value.isEmpty() ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 文本行列表（优先读取 YAML 列表，其次读取单行字符串）。 */
    public List<String> lines(String path) {
        List<String> list = lists.get(path);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        String single = raw(path);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    public String prefixPlain() {
        return Text.plain(prefixRaw);
    }

    public String format(String path, PlaceholderContext ctx) {
        String text = raw(path);
        return ctx == null ? text : ctx.apply(text);
    }

    /** 渲染为组件：自动解析 PlaceholderAPI 与 MiniMessage。 */
    public Component component(String path, Player player, PlaceholderContext ctx) {
        String text = format(path, ctx);
        if (player != null) {
            text = PapiHook.apply(player, text);
        }
        return Text.mm(text);
    }

    /** 渲染带前缀的组件。 */
    public Component prefixed(String path, Player player, PlaceholderContext ctx) {
        String text = prefixRaw + format(path, ctx);
        if (player != null) {
            text = PapiHook.apply(player, text);
        }
        return Text.mm(text);
    }

    /** 渲染多行文本。 */
    public List<Component> components(String path, Player player, PlaceholderContext ctx) {
        return lines(path).stream().map(line -> {
            String text = ctx == null ? line : ctx.apply(line);
            if (player != null) {
                text = PapiHook.apply(player, text);
            }
            return Text.mm(text);
        }).toList();
    }

    /** 发送提示消息（带前缀）。 */
    public void send(CommandSender target, String path, Player player, PlaceholderContext ctx) {
        if (target == null) {
            return;
        }
        String text = raw(path);
        if (text.isEmpty()) {
            return;
        }
        String body = prefixRaw + (ctx == null ? text : ctx.apply(text));
        if (player != null) {
            body = PapiHook.apply(player, body);
        }
        // 控制台不支持 MiniMessage，转换为 § 颜色代码，保证日志可读
        if (!(target instanceof Player)) {
            target.sendMessage(Text.legacy(body));
            return;
        }
        // 玩家走 Adventure 的 Audience 接口，避免解析已废弃的 BungeeCord 重载
        Text.send((net.kyori.adventure.audience.Audience) target, body);
    }

    /** 广播消息（不加前缀，走 broadcast 模板）。 */
    public String broadcast(String path, Player player, PlaceholderContext ctx) {
        String text = raw(path);
        String body = ctx == null ? text : ctx.apply(text);
        return player != null ? PapiHook.apply(player, body) : body;
    }

    public boolean contains(String path) {
        return values.containsKey(path);
    }

    public String pathKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    /** 代码内置默认文案：配置缺失时的兜底，全部使用 MiniMessage。 */
    private static final class Defaults {
        private static final Map<String, String> TEXT = new LinkedHashMap<>();

        private Defaults() {
        }

        private static void put(String key, String value) {
            TEXT.put(key, value);
        }

        static {
            put(K.PLAYER_ONLY, "<red>该命令只能由玩家执行。");
            put(K.NO_PERMISSION, "<red>你没有权限执行该操作。");
            put(K.UNKNOWN_SUB, "<red>未知的子命令，请使用 <yellow>/lottery help<red>。");
            put(K.RELOADED, "<green>配置已重新加载，共载入 <white>%pools%<green> 个卡池。");
            put(K.USAGE, "<gray>用法：<yellow>/lottery <open|draw|pity|rates|reload|give|reset>");
            put(K.UNKNOWN_POOL, "<red>找不到卡池 <yellow>%pool%<red>。");
            put(K.POOL_LOCKED, "<red>你还不满足卡池 <yellow>%pool_name%<red> 的参与条件。");
            put(K.POOL_EMPTY, "<red>卡池 <yellow>%pool_name%<red> 暂无可抽取的奖品。");
            put("dialog.currency-unavailable", "<red>✖ 该卡池的货币 <yellow>%currency%<red> 当前不可用，请联系管理员。");
            put(K.CURRENCY_MISSING, "<red>该卡池配置的货币 <yellow>%currency%<red> 当前不可用。");
            put(K.COOLDOWN, "<red>操作过于频繁，请等待 <yellow>%time%<red> 秒后再试。");
            put(K.BUSY, "<red>上一次抽奖还在结算中，请稍候。");
            put(K.INSUFFICIENT, "<red>余额不足：需要 <yellow>%cost% %currency%<red>，当前仅有 <yellow>%balance% %currency%<red>。");
            put(K.CHARGE_FAILED, "<red>扣款失败，抽奖已取消，请稍后再试。");
            put(K.DRAW_FAILED, "<red>抽奖过程中出现异常，已取消本次消耗。");
            put(K.DRAW_SUCCESS, "<green>抽奖完成！消耗 <yellow>%cost% %currency%<green>，获得：<white>%summary%");
            put(K.DRAW_EMPTY, "<yellow>本次没有可抽取的奖品（可能已全部达到上限）。");
            put(K.DRAW_LIMITED, "<gray>部分奖品已达上限，本次仅发放 <white>%count%<gray> 件。");
            put(K.BAG_FULL, "<yellow>背包空间不足，<white>%dropped%<yellow> 件奖品已掉落在你脚下。");
            put(K.PITY_TRIGGERED, "<gold>✨ 保底触发！<yellow>%rarity_name%<gold> 品质必得！");
            put("dialog.pity-ready", " <gold>【已就绪】");
            put("dialog.pity-will-guarantee", "<gold>　　下一次抽取必得该品质或更高");
            put(K.LUCKY, "<gold>✨ 欧皇时刻！一发入魂 <yellow>%prize_name%<gold>！");
            put(K.GIVE_SELF, "<green>已为你发放奖品 <white>%prize_name%<green> ×%amount%。");
            put(K.GIVE_OTHER, "<green>已向 <white>%target%<green> 发放奖品 <white>%prize_name%<green> ×%amount%。");
            put(K.GIVE_RECEIVED, "<green>你收到了来自管理员的奖品：<white>%prize_name%<green> ×%amount%。");
            put(K.GIVE_FAILED, "<red>发放失败：找不到奖品 <yellow>%prize%<red> 或物品无法给予。");
            put(K.PLAYER_NOT_FOUND, "<red>找不到玩家 <yellow>%target%<red>。");
            put(K.RESET_DONE, "<green>已重置 <white>%target%<green> 在卡池 <white>%pool_name%<green> 的保底与统计。");
            put(K.NO_POOLS, "<red>当前没有任何可用卡池，请检查 config.yml。");
            put(K.BROADCAST, "<gray>[<gold>抽奖<gray>] <white>%player% <gray>在 <white>%pool_name% <gray>中抽到了 %rarity_color%%prize_name%<gray>！");
            put(K.BROADCAST_LUCKY, "<gray>[<gold>抽奖<gray>] <gold>欧皇降临！<white>%player% <gray>一发入魂 <gold>%prize_name%<gray>！");
            put(K.DIALOG_CLOSE, "关闭");
            put(K.DIALOG_BACK, "返回");
            put(K.DIALOG_AMOUNT_LABEL, "抽奖次数");
            put(K.DIALOG_POOL_LABEL, "选择卡池");
        }
    }
}
