package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.physics.mlp.DualInferenceResult;
import dev.ztros.ansac.physics.mlp.ModelSelector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推理显示管理器。
 * <p>
 * 将双模型 AB 架构的推理结果实时展示给管理员。
 * Folia 不支持 Scoreboard 多实例（getNewScoreboard 抛 UnsupportedOperationException），
 * 因此使用 BossBar 作为显示载体——Folia 完全兼容。
 * </p>
 * <p>
 * BossBar 最多显示标题文字，推理详情通过分页 BossBar 标题 + 定期聊天补丁展示。
 * 管理员可同时监控不同目标玩家，每 2 秒（40 tick）更新一次。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public class InferenceScoreboardManager {

    private final ANSACPlugin plugin;

    /** 管理员 UUID -> 监控数据 */
    private final ConcurrentHashMap<UUID, WatchData> activeWatches = new ConcurrentHashMap<>();

    /** 更新间隔（tick）—— 40 tick = 2 秒 */
    private static final int UPDATE_INTERVAL_TICKS = 40;

    /** 每个管理员的监控数据 */
    private static class WatchData {
        final UUID targetUuid;
        BossBar bossBar;

        WatchData(UUID targetUuid) {
            this.targetUuid = targetUuid;
        }
    }

    public InferenceScoreboardManager(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动定时更新器（全局区域线程，Folia 兼容）。
     */
    public void start() {
        plugin.getSchedulerAdapter().runTimer(this::updateAll, 10L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * 为管理员开启目标玩家的推理 BossBar。
     *
     * @param admin  执行命令的管理员
     * @param target 被监控的目标玩家
     * @return true 如果成功开启
     */
    public boolean startWatching(Player admin, Player target) {
        UUID adminUuid = admin.getUniqueId();

        // 如果已在监控，先关闭
        stopWatching(adminUuid);

        WatchData data = new WatchData(target.getUniqueId());

        // 创建 BossBar
        BossBar bossBar = Bukkit.createBossBar(
            ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ANSAC AI 推理: " + ChatColor.WHITE + target.getName(),
            BarColor.BLUE, BarStyle.SEGMENTED_10, BarFlag.PLAY_BOSS_MUSIC
        );
        bossBar.setProgress(0.0);
        bossBar.addPlayer(admin);
        bossBar.setVisible(true);
        data.bossBar = bossBar;

        activeWatches.put(adminUuid, data);

        // 立即更新一次
        updateWatch(adminUuid, data, target.getName());

        return true;
    }

    /**
     * 停止管理员的推理 BossBar。
     */
    public void stopWatching(UUID adminUuid) {
        WatchData data = activeWatches.remove(adminUuid);
        if (data == null) return;

        if (data.bossBar != null) {
            data.bossBar.removeAll();
            data.bossBar.setVisible(false);
            data.bossBar = null;
        }
    }

    /** 管理员是否正在查看推理 BossBar */
    public boolean isWatching(UUID adminUuid) {
        return activeWatches.containsKey(adminUuid);
    }

    /** 获取管理员当前监控的目标 UUID */
    public UUID getWatchTarget(UUID adminUuid) {
        WatchData data = activeWatches.get(adminUuid);
        return data != null ? data.targetUuid : null;
    }

    /** 获取所有正在查看的管理员 UUID */
    public Set<UUID> getWatchingAdmins() {
        return activeWatches.keySet();
    }

    /** 关闭所有 BossBar */
    public void shutdown() {
        for (UUID uuid : Set.copyOf(activeWatches.keySet())) {
            stopWatching(uuid);
        }
    }

    // ==================== 内部更新逻辑 ====================

    private void updateAll() {
        for (UUID adminUuid : Set.copyOf(activeWatches.keySet())) {
            Player admin = plugin.getServer().getPlayer(adminUuid);
            if (admin == null || !admin.isOnline()) {
                stopWatching(adminUuid);
                continue;
            }

            WatchData data = activeWatches.get(adminUuid);
            if (data == null) continue;

            Player target = plugin.getServer().getPlayer(data.targetUuid);
            if (target == null || !target.isOnline()) {
                admin.sendMessage(ChatColor.RED + "目标玩家已下线，推理 BossBar 已关闭。");
                stopWatching(adminUuid);
                continue;
            }

            updateWatch(adminUuid, data, target.getName());
        }
    }

    private void updateWatch(UUID adminUuid, WatchData data, String targetName) {
        if (data.bossBar == null) return;

        DualInferenceResult result = plugin.getPhysicsInferenceService()
            .getDualInferenceResult(data.targetUuid);

        if (result == DualInferenceResult.EMPTY) {
            data.bossBar.setTitle(
                ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ANSAC AI: " +
                ChatColor.WHITE + targetName + " " +
                ChatColor.GRAY + "(等待数据...)"
            );
            data.bossBar.setProgress(0.0);
            data.bossBar.setColor(BarColor.WHITE);
            return;
        }

        // 构建标题：综合置信度 + 来源
        double maxAnomaly = Math.max(result.normalAnomalyScore(), result.threatFusionScore());
        double confidence = maxAnomaly;

        BarColor barColor;
        String sourceLabel = "N/A";
        ChatColor sourceColor = ChatColor.GRAY;

        if (result.selectorResult() != null) {
            ModelSelector.ModelSelectorResult sr = result.selectorResult();
            confidence = sr.confidence();
            sourceLabel = getSourceLabel(sr.source());
            sourceColor = getSourceChatColor(sr.source());
            barColor = getBarColor(sr);
        } else {
            barColor = maxAnomaly >= 0.5 ? BarColor.RED : BarColor.GREEN;
        }

        // 构建详细标题
        StringBuilder title = new StringBuilder();
        title.append(ChatColor.DARK_AQUA).append(ChatColor.BOLD).append("AI: ");
        title.append(ChatColor.WHITE).append(targetName).append(" ");
        title.append(sourceColor).append("[").append(sourceLabel).append("] ");
        title.append(confidence >= 0.75 ? ChatColor.RED : ChatColor.YELLOW);
        title.append(String.format("%.0f%%", confidence * 100));

        // 状态标记
        if (result.isHighRisk()) {
            title.append(" ").append(ChatColor.DARK_RED).append("[高危]");
        }
        if (result.isRealtimeInference()) {
            title.append(" ").append(ChatColor.AQUA).append("[实时]");
        }

        // A/B 模型分数摘要
        title.append(ChatColor.GRAY).append(" | ");
        title.append(ChatColor.YELLOW).append("A:");
        title.append(result.normalMovementScore() >= 0.5 ? ChatColor.GREEN : ChatColor.RED);
        title.append(String.format("%.0f", result.normalAnomalyScore() * 100)).append("%");

        if (plugin.getPhysicsInferenceService().isDualModelEnabled()) {
            title.append(ChatColor.GRAY).append(" B:");
            title.append(result.threatFusionScore() >= 0.5 ? ChatColor.RED : ChatColor.GREEN);
            title.append(String.format("%.0f", result.threatFusionScore() * 100)).append("%");
        }

        data.bossBar.setTitle(title.toString());
        data.bossBar.setProgress(Math.max(0.0, Math.min(1.0, confidence)));
        data.bossBar.setColor(barColor);
    }

    private String getSourceLabel(ModelSelector.VerdictSource source) {
        return switch (source) {
            case DUAL_CONFIRM -> "双重确认";
            case MODEL_B_ONLY -> "B模型命中";
            case MODEL_A_ONLY -> "A模型异常";
            case INSUFFICIENT -> "证据不足";
        };
    }

    private ChatColor getSourceChatColor(ModelSelector.VerdictSource source) {
        return switch (source) {
            case DUAL_CONFIRM -> ChatColor.DARK_RED;
            case MODEL_B_ONLY -> ChatColor.RED;
            case MODEL_A_ONLY -> ChatColor.YELLOW;
            case INSUFFICIENT -> ChatColor.GREEN;
        };
    }

    private BarColor getBarColor(ModelSelector.ModelSelectorResult sr) {
        if (sr.source() == ModelSelector.VerdictSource.DUAL_CONFIRM) return BarColor.RED;
        if (sr.source() == ModelSelector.VerdictSource.MODEL_B_ONLY) return BarColor.RED;
        if (sr.source() == ModelSelector.VerdictSource.MODEL_A_ONLY) return BarColor.YELLOW;
        return BarColor.GREEN;
    }
}
