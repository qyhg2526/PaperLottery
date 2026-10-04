package cn.dsh.lottery;

import cn.dsh.lottery.animation.AnimationRunner;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.data.DataStore;
import cn.dsh.lottery.lottery.LotteryConfig;
import cn.dsh.lottery.lottery.LotteryService;
import cn.dsh.lottery.lottery.RewardApplier;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.ui.UiManager;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PaperLottery —— 基于 Paper Dialog API 的多货币抽奖插件。
 * <p>
 * 主要能力：
 * <ul>
 *     <li>通过 Vault 接入任意经济插件，并支持 Vault 多货币账户、原版经验、原版物品等自定义货币；</li>
 *     <li>使用 Paper 26.2 的 Dialog API 作为全部菜单界面（主界面 / 结果 / 概率 / 保底 / 记录 / 管理）；</li>
 *     <li>权重概率、连抽保底、每日与总量限额、全服播报、PlaceholderAPI 变量、MiniMessage 文案。</li>
 * </ul>
 */
public final class PaperLotteryPlugin extends JavaPlugin {

    private static PaperLotteryPlugin instance;

    private LotteryConfig lotteryConfig;
    private LotteryService lotteryService;
    private DataStore dataStore;
    private UiManager uiManager;
    private PluginContext ctx;
    private AnimationRunner animationRunner;

    public static PaperLotteryPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.lotteryConfig = new LotteryConfig(this);
        this.lotteryConfig.load();
        this.dataStore = new DataStore(this);
        this.dataStore.load();

        // 上下文采用「先建容器 → 回填引用」的方式，避免相互依赖导致构造顺序问题
        this.ctx = new PluginContext(this, lotteryConfig, dataStore);
        this.lotteryService = new LotteryService(this, ctx);
        this.animationRunner = new AnimationRunner(this, ctx);
        this.lotteryService.bindAnimation(animationRunner);
        this.uiManager = new UiManager(this, ctx);
        this.ctx.service(lotteryService).ui(uiManager).animation(animationRunner);

        registerCommand();
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new AnimationListener(this), this);
        startAutoSave();

        if (!lotteryConfig.currencies().hasVault()) {
            getLogger().warning("未检测到可用的 Vault 经济插件，依赖 vault 的货币将不可用；"
                    + "可使用 xp:levels / item:diamond 等内置货币先跑通流程。");
        }
        getLogger().info("PaperLottery 已启用（Paper 26.2 / Dialog API " + (uiManager.supported() ? "可用" : "不可用")
                + " / 抽奖动画 " + (lotteryConfig.settings().animation().active()
                        ? lotteryConfig.settings().animation().type().label() : "已关闭") + "）。");
    }

    @Override
    public void onDisable() {
        if (animationRunner != null) {
            // 先恢复所有玩家的快捷栏，避免动画中被关服导致物品丢失
            animationRunner.shutdown();
        }
        if (dataStore != null) {
            dataStore.shutdown();
        }
        getLogger().info("PaperLottery 已卸载，玩家数据已保存。");
    }

    /** 重新加载配置与数据（命令与对话框均可触发）。 */
    public void reloadAll() {
        // 先结束动画并落盘，避免重载过程中丢失物品或计数
        if (animationRunner != null) {
            animationRunner.shutdown();
        }
        dataStore.save();
        lotteryConfig.load();
        dataStore.load();
        // 重建服务与界面管理器，使新配置立即生效
        this.lotteryService = new LotteryService(this, ctx);
        this.animationRunner = new AnimationRunner(this, ctx);
        this.lotteryService.bindAnimation(animationRunner);
        this.uiManager = new UiManager(this, ctx);
        this.ctx.service(lotteryService).ui(uiManager).animation(animationRunner);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("lottery");
        if (command == null) {
            getLogger().severe("无法注册命令 /lottery，请检查 plugin.yml。");
            return;
        }
        LotteryCommand executor = new LotteryCommand(this, () -> ctx);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void startAutoSave() {
        // 每 5 分钟保存一次（仅在数据变动时真正写盘）
        long interval = 20L * 60L * 5L;
        Bukkit.getScheduler().runTaskTimer(this, () -> dataStore.saveIfDirty(), interval, interval);
    }

    // ------------------------------------------------------------------ 便捷访问

    public PluginContext ctx() {
        return ctx;
    }

    public LotteryConfig lotteryConfig() {
        return lotteryConfig;
    }

    public LotteryService lotteryService() {
        return lotteryService;
    }

    public DataStore dataStore() {
        return dataStore;
    }

    public UiManager ui() {
        return uiManager;
    }

    /** 抽奖动画引擎。 */
    public AnimationRunner animation() {
        return animationRunner;
    }

    public Messages messages() {
        return lotteryConfig.messages();
    }

    // ------------------------------------------------------------------ 管理接口

    /**
     * 直接给玩家发放某个奖品（管理命令 / 离线补发使用）。
     *
     * @return 是否发放成功
     */
    public boolean givePrize(Player target, Pool pool, Prize prize, int amount) {
        if (target == null || prize == null) {
            return false;
        }
        for (int i = 0; i < Math.max(1, amount); i++) {
            RewardApplier.apply(this, lotteryConfig.currencies(), target, pool, prize,
                    lotteryConfig.settings().dropWhenFull());
        }
        PlaceholderContext ph = PlaceholderContext.of(target).pool(pool).prize(prize).num("amount", amount);
        messages().send(target, Messages.K.GIVE_RECEIVED, target, ph);
        return true;
    }

    /** 从控制台以文本形式反馈（用于 reload 等命令）。 */
    public void feedback(org.bukkit.command.CommandSender sender, String path, PlaceholderContext ph) {
        Player player = sender instanceof Player p ? p : null;
        messages().send(sender, path, player, ph);
    }

    /** 调试日志。 */
    public void debug(String message) {
        if (lotteryConfig.settings().debug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    /** 带前缀的组件，供其它插件调用。 */
    public net.kyori.adventure.text.Component prefixed(String message) {
        return Text.mm(message);
    }
}
