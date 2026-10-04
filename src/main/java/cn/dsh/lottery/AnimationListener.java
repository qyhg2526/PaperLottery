package cn.dsh.lottery;

import cn.dsh.lottery.animation.AnimationRunner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * 抽奖动画的事件守卫：
 * <ul>
 *     <li>动画期间禁止点击 / 拖拽转盘界面，防止玩家把转盘上的展示物品拿走；</li>
 *     <li>动画期间玩家主动关闭界面时立即结束动画并结算，避免奖品丢失；</li>
 *     <li>玩家退出时恢复被临时借用的快捷栏。</li>
 * </ul>
 */
public final class AnimationListener implements Listener {

    private final PaperLotteryPlugin plugin;

    public AnimationListener(PaperLotteryPlugin plugin) {
        this.plugin = plugin;
    }

    private AnimationRunner runner() {
        return plugin.animation();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        AnimationRunner runner = runner();
        if (runner == null) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (runner.isAnimatingView(top)) {
            // 转盘只是演出，任何操作都不应生效
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        AnimationRunner runner = runner();
        if (runner == null) {
            return;
        }
        if (runner.isAnimatingView(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        AnimationRunner runner = runner();
        if (runner == null || !(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (event.getReason() == InventoryCloseEvent.Reason.DISCONNECT
                || event.getReason() == InventoryCloseEvent.Reason.PLUGIN) {
            // 退出与插件主动关闭由其它路径处理，避免重复结算
            return;
        }
        runner.handleClose(player, event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        AnimationRunner runner = runner();
        if (runner != null) {
            runner.handleQuit(event.getPlayer());
        }
    }
}
