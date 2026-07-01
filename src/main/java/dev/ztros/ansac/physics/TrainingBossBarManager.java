package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BossBar 训练进度管理器。
 * <p>
 * 为在线管理员实时展示 A/B 模型的训练进度，替代控制台日志。
 * 每 N tick 更新一次，显示：
 * - A 模型（正常模型）在线训练样本数
 * - B 模型（威胁模型）在线训练样本数
 * - 危险玩家威胁度（如有）
 * </p>
 */
public class TrainingBossBarManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ANSACPlugin plugin;
    private final PhysicsInferenceService svc;

    // 每个管理员一个 BossBar
    private final ConcurrentHashMap<UUID, BossBar> adminBars = new ConcurrentHashMap<>();

    public TrainingBossBarManager(ANSACPlugin plugin, PhysicsInferenceService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    /**
     * 启动定时更新任务（每 2 秒更新一次）
     */
    public void start() {
        plugin.getSchedulerAdapter().runTimer(() -> {
            updateAll();
        }, 40L, 40L); // 40 tick = 2 秒
    }

    private void updateAll() {
        long aCount = svc.getAModelTrainCount();
        long bCount = svc.getBModelTrainCount();
        int highRiskCount = svc.getHighRiskPlayers().size();
        int trustedCount = svc.getTrustedPlayerCount();
        boolean dualEnabled = svc.isDualModelEnabled();

        // 构建标题
        StringBuilder title = new StringBuilder();
        title.append("<gray>[<aqua>ANSAC 训练</aqua>]</gray> ");

        if (dualEnabled) {
            title.append("<green>A模型:</green> <white>").append(aCount).append("</white> 样本");
            title.append(" <dark_gray>|</dark_gray> ");
            title.append("<light_purple>B模型:</light_purple> <white>").append(bCount).append("</white> 样本");
        } else {
            title.append("<green>模型:</green> <white>").append(aCount).append("</white> 样本");
        }

        if (highRiskCount > 0) {
            title.append(" <dark_gray>|</dark_gray> <red>危险玩家: ").append(highRiskCount).append("</red>");
        }
        if (trustedCount > 0) {
            title.append(" <dark_gray>|</dark_gray> <green>信任玩家: ").append(trustedCount).append("</green>");
        }

        Component titleComponent = MM.deserialize(title.toString());

        // 进度：A模型样本数 / 目标（目标设为 10000，满后循环）
        double progress = Math.min(1.0, (aCount % 10000) / 10000.0);
        BossBar.Color color = dualEnabled && bCount > 0 ? BossBar.Color.PURPLE : BossBar.Color.BLUE;

        // 更新所有管理员的 BossBar
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.hasPermission("ansac.admin")) continue;

            BossBar bar = adminBars.computeIfAbsent(p.getUniqueId(), id ->
                BossBar.bossBar(titleComponent, (float) progress, color, BossBar.Overlay.PROGRESS)
            );

            bar.title(titleComponent);
            bar.progress((float) progress);
            bar.color(color);

            // 确保玩家看到 BossBar
            if (!bar.viewers().contains(p)) {
                p.showBossBar(bar);
            }
        }

        // 清理离线管理员的 BossBar
        adminBars.keySet().removeIf(uuid -> {
            Player p = plugin.getServer().getPlayer(uuid);
            return p == null;
        });
    }

    /**
     * 关闭所有 BossBar
     */
    public void shutdown() {
        for (BossBar bar : adminBars.values()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
        }
        adminBars.clear();
    }
}
