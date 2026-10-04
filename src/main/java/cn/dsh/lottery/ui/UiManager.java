package cn.dsh.lottery.ui;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.PluginContext;
import cn.dsh.lottery.model.DrawOutcome;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.util.Text;
import org.bukkit.entity.Player;

/**
 * 界面管理器：统一入口，负责检测 Dialog API 是否可用并转发到 {@link DialogManager}。
 * <p>
 * Paper 26.2 内置 Dialog API；若运行在不支持该 API 的服务端核心上，插件会给出友好提示而不是报错。
 */
public final class UiManager {

    private final PaperLotteryPlugin plugin;
    private final PluginContext ctx;
    private final DialogManager dialogManager;
    private final boolean supported;

    public UiManager(PaperLotteryPlugin plugin, PluginContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
        DialogManager manager = null;
        boolean ok;
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            manager = new DialogManager(plugin, ctx);
            ok = true;
        } catch (Throwable t) {
            plugin.getLogger().warning("当前服务端核心不支持 Dialog API，抽奖界面将不可用（请使用 Paper 1.21.6+ / 26.x）。"
                    + " 原因：" + t.getMessage());
            ok = false;
        }
        this.dialogManager = manager;
        this.supported = ok;
    }

    public boolean supported() {
        return supported;
    }

    public DialogManager dialogs() {
        return dialogManager;
    }

    public void openMain(Player player) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openMain(player);
    }

    public void openMain(Player player, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openMain(player, pool, null, null);
    }

    public void openRates(Player player, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openRates(player, pool);
    }

    public void openPity(Player player, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openPity(player, pool);
    }

    public void openHistory(Player player, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openHistory(player, pool);
    }

    public void openResult(Player player, DrawOutcome result, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openResult(player, result, pool);
    }

    public void openAdmin(Player player, Pool pool) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.openAdmin(player, pool);
    }

    /** 直接抽奖（命令行入口，仍会经过二次确认配置）。 */
    public void draw(Player player, Pool pool, int amount) {
        if (!ensureSupported(player)) {
            return;
        }
        dialogManager.handleDrawRequest(player, pool, amount);
    }

    private boolean ensureSupported(Player player) {
        if (supported) {
            return true;
        }
        Text.send(player, "<red>当前服务端核心不支持 Dialog API，无法打开抽奖界面。");
        return false;
    }

    /**
     * 只做结算，不播放动画、不打开结果界面。
     * <p>
     * 供动画被外部打断（插件卸载、玩家退出）时兜底，保证奖励与播报不丢失。
     */
    public void completeOnly(DrawOutcome outcome) {
        ctx.service().complete(outcome);
    }

    public String summarise(DrawOutcome result) {
        return dialogManager == null ? "" : dialogManager.summarise(result);
    }
}
