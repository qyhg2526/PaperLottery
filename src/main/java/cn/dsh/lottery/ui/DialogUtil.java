package cn.dsh.lottery.ui;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.PluginContext;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import cn.dsh.lottery.util.ItemBuilder;
import cn.dsh.lottery.util.PapiHook;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话框构建工具：把配置中的文本与物品转换成 Paper Dialog API 组件。
 */
public final class DialogUtil {

    /** 点击回调接口：接收对话框响应数据。 */
    @FunctionalInterface
    public interface ResponseHandler {
        void handle(Player player, io.papermc.paper.dialog.DialogResponseView response);
    }

    private DialogUtil() {
    }

    /**
     * 构建对话框正文。
     * <p>
     * 说明：原版 {@code item} 正文组件要求物品字段完整（数量区间、组件补丁齐全），
     * 直接由 {@link ItemStack} 构造在部分服务端构建上会被判定为非法数据包。
     * 为保证在任何 Paper 26.2 构建上都能稳定打开，默认使用带品质颜色的文本正文，
     * 仅在配置显式开启 {@code settings.dialog.use-item-body: true} 时使用物品组件。
     */
    public static List<DialogBody> bodies(PluginContext ctx, List<Component> lines, ItemStack item, List<Component> itemLore) {
        List<DialogBody> bodies = new ArrayList<>();
        if (ctx.settings().useItemBody() && item != null) {
            try {
                var builder = DialogBody.item(item);
                if (itemLore != null && !itemLore.isEmpty()) {
                    builder.description(DialogBody.plainMessage(joinLines(itemLore), ctx.settings().bodyWidth()));
                }
                bodies.add(builder.width(16).height(16).build());
            } catch (Throwable t) {
                PaperLotteryPlugin.get().getLogger()
                        .warning("物品正文组件不可用，已回退为文本显示：" + t.getMessage());
            }
        }
        for (Component line : lines) {
            bodies.add(DialogBody.plainMessage(line, ctx.settings().bodyWidth()));
        }
        return bodies;
    }

    /** 纯文本正文。 */
    public static List<DialogBody> text(PluginContext ctx, List<Component> lines) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component line : lines) {
            bodies.add(DialogBody.plainMessage(line, ctx.settings().bodyWidth()));
        }
        return bodies;
    }

    /** 创建动作按钮。 */
    public static ActionButton button(Component label, Component tooltip, int width, DialogAction action) {
        return ActionButton.create(label, tooltip, width, action);
    }

    /** 创建“执行命令”按钮。 */
    public static ActionButton commandButton(Component label, Component tooltip, int width, String command) {
        return ActionButton.create(label, tooltip, width, DialogAction.commandTemplate(command));
    }

    /** 创建“静态链接”按钮。 */
    public static ActionButton linkButton(Component label, Component tooltip, int width, net.kyori.adventure.text.event.ClickEvent event) {
        return ActionButton.create(label, tooltip, width, DialogAction.staticAction(event));
    }

    /** 创建带回调的按钮（点击后执行自定义逻辑，可读取输入框内容）。 */
    public static ActionButton callbackButton(Component label, Component tooltip, int width, ResponseHandler handler) {
        return ActionButton.create(label, tooltip, width, DialogActions.customClick((response, audience) -> {
            if (audience instanceof Player clicker) {
                handler.handle(clicker, response);
            }
        }));
    }

    /** 构建对话框基础信息。 */
    public static DialogBase base(PluginContext ctx, Component title, List<DialogBody> bodies, List<DialogInput> inputs) {
        return DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(ctx.settings().afterAction())
                .body(bodies)
                .inputs(inputs)
                .build();
    }

    public static DialogBase base(PluginContext ctx, Component title, List<DialogBody> bodies) {
        return base(ctx, title, bodies, List.of());
    }

    /** 构建“多按钮”对话框类型。 */
    public static DialogType multiAction(List<ActionButton> buttons, ActionButton exitAction, int columns) {
        return DialogType.multiAction(buttons, exitAction, columns);
    }

    /** 构建“提示”对话框类型（仅一个确认按钮）。 */
    public static DialogType notice(ActionButton action) {
        return action == null ? DialogType.notice() : DialogType.notice(action);
    }

    /**
     * 构建“单选项”输入组件。
     *
     * @param key     响应 key（通过 {@code response.getText(key)} 读取）
     * @param label   标签
     * @param options 选项列表
     * @param initial 默认选中项 id
     */
    public static DialogInput singleOption(String key,
                                           Component label,
                                           List<Option> options,
                                           String initial,
                                           int width) {
        List<SingleOptionDialogInput.OptionEntry> entries = options.stream()
                .map(o -> SingleOptionDialogInput.OptionEntry.create(o.id(), o.display(), o.id().equals(initial)))
                .toList();
        // 使用 DialogInput 工厂方法（SingleOptionDialogInput 只暴露读取方法）
        return DialogInput.singleOption(key, width, entries, label, true);
    }

    /** 构建文本输入组件。 */
    public static DialogInput textInput(String key, Component label, String initialValue, int maxLength, int width) {
        return DialogInput.text(key, label)
                .initial(initialValue == null ? "" : initialValue)
                .maxLength(Math.max(1, maxLength))
                .width(width)
                .build();
    }

    /** 下拉选项。 */
    public record Option(String id, Component display) {
    }

    /** 从响应中安全读取文本（不存在时返回默认值）。 */
    public static String readText(io.papermc.paper.dialog.DialogResponseView response, String key, String def) {
        if (response == null) {
            return def;
        }
        try {
            String value = response.getText(key);
            return value == null || value.isBlank() ? def : value;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 从响应中安全读取数值。 */
    public static int readInt(io.papermc.paper.dialog.DialogResponseView response, String key, int def) {
        String raw = readText(response, key, null);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 常用材质，找不到时回退。 */
    public static Material materialOr(String name, Material fallback) {
        Material material = ItemBuilder.material(name);
        return material == null || material.isAir() ? fallback : material;
    }

    /** 把纯文本行渲染为组件（应用内置占位符与 PlaceholderAPI）。 */
    public static List<Component> render(Player player, List<String> raw, PlaceholderContext ph) {
        return raw.stream().map(line -> {
            String text = ph == null ? line : ph.apply(line);
            text = PapiHook.apply(player, text);
            return Text.mm(text);
        }).toList();
    }

    /** 单个组件渲染。 */
    public static Component render(Player player, String raw, PlaceholderContext ph) {
        String text = ph == null ? raw : ph.apply(raw);
        return Text.mm(PapiHook.apply(player, text));
    }

    private static Component joinLines(List<Component> lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(i));
        }
        return result;
    }
}
