package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BossBar 训练进度管理器。
 * <p>
 * 为在线管理员实时展示 A/B 模型的训练进度，替代控制台日志。
 * 每 2 秒更新一次，显示：
 * - A 模型（正常模型）在线训练样本数
 * - B 模型（威胁模型）在线训练样本数
 * - 危险/信任玩家数量
 * </p>
 */
public class TrainingBossBarManager {

    private final ANSACPlugin plugin;
    private final PhysicsInferenceService svc;
    private final ConcurrentHashMap<UUID, BossBar> adminBars = new ConcurrentHashMap<>();

    public TrainingBossBarManager(ANSACPlugin plugin, PhysicsInferenceService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    public void start() {
        plugin.getSchedulerAdapter().runTimer(() -> {
            updateAll();
        }, 40L, 40L);
    }

    @SuppressWarnings("deprecation")
    private void updateAll() {
        long aCount = svc.getAModelTrainCount();
        long bCount = svc.getBModelTrainCount();
        int highRiskCount = svc.getHighRiskPlayers().size();
        int trustedCount = svc.getTrustedPlayerCount();
        boolean dualEnabled = svc.isDualModelEnabled();

        // 构建标题
        StringBuilder title = new StringBuilder();
        title.append("§b[ANSAC 训练] ");

        if (dualEnabled) {
            title.append("§aA模型: §f").append(aCount).append(" §7| §d");
            title.append("B模型: §f").append(bCount).append(" 样本");
        } else {
            title.append("§a模型: §f").append(aCount).append(" 样本");
        }

        if (highRiskCount > 0) {
            title.append(" §7| §c危险: ").append(highRiskCount);
        }
        if (trustedCount > 0) {
            title.append(" §7| §a信任: ").append(trustedCount);
        }

        BarColor color = (dualEnabled && bCount > 0) ? BarColor.PURPLE : BarColor.BLUE;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("ansac.admin")) continue;

            BossBar bar = adminBars.computeIfAbsent(p.getUniqueId(), id -> {
                BossBar b = Bukkit.createBossBar(title.toString(), color, BarStyle.SEGMENTED_10);
                b.setProgress(0.0);
                b.addPlayer(p);
                b.setVisible(true);
                return b;
            });

            bar.setTitle(title.toString());
            bar.setColor(color);
            // 进度循环显示：每 10000 样本一个循环
            double progress = (aCount % 10000) / 10000.0;
            bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        }

        // 清理离线管理员的 BossBar
        adminBars.keySet().removeIf(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                BossBar bar = adminBars.get(uuid);
                if (bar != null) {
                    bar.removeAll();
                }
                return true;
            }
            return false;
        });
    }

    public void shutdown() {
        for (BossBar bar : adminBars.values()) {
            bar.removeAll();
        }
        adminBars.clear();
    }
}
