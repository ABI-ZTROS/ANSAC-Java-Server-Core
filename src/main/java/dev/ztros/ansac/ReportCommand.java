package dev.ztros.ansac;

import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.mlp.DualInferenceResult;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.player.PlayerDataManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家互相举报命令处理器。
 * 所有玩家均可使用，无需 OP 权限。
 * 举报后系统对被举报玩家执行快速检测扫描，
 * 若检测到作弊行为则踢出，否则通知举报人举报无效。
 */
public class ReportCommand implements CommandExecutor {

    private final ANSACPlugin plugin;

    /** 举报人冷却映射：UUID -> 上次举报时间戳(ms) */
    private final Map<UUID, Long> reporterCooldown = new ConcurrentHashMap<>();
    /** 被举报玩家冷却映射：UUID -> 上次被举报时间戳(ms) */
    private final Map<UUID, Long> reportedCooldown = new ConcurrentHashMap<>();

    /** 举报人冷却时间：60秒 */
    private static final long REPORTER_COOLDOWN_MS = 60_000L;
    /** 被举报玩家冷却时间：30秒 */
    private static final long REPORTED_COOLDOWN_MS = 30_000L;
    /** VL 阈值：总 VL > 此值视为作弊 */
    private static final int VL_THRESHOLD = 5;
    /** 推理异常度阈值：异常度 > 此值视为作弊 */
    private static final double ANOMALY_THRESHOLD = 0.65;
    /** 失败检测项阈值：失败项数 >= 此值视为作弊 */
    private static final int FAILED_CHECKS_THRESHOLD = 2;

    public ReportCommand(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return true;
        }

        UUID reporterUuid = reporter.getUniqueId();
        long now = System.currentTimeMillis();

        // 检查举报人冷却
        Long lastReport = reporterCooldown.get(reporterUuid);
        if (lastReport != null && (now - lastReport) < REPORTER_COOLDOWN_MS) {
            long remaining = (REPORTER_COOLDOWN_MS - (now - lastReport)) / 1000L;
            reporter.sendMessage(Component.text("举报冷却中，请等待 " + remaining + " 秒后再举报。", NamedTextColor.YELLOW));
            return true;
        }

        if (args.length < 1) {
            reporter.sendMessage(Component.text("用法: /report <玩家名> [原因]", NamedTextColor.RED));
            return true;
        }

        String targetName = args[0];
        String reason = args.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "玩家举报";

        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            reporter.sendMessage(Component.text("找不到在线玩家: " + targetName, NamedTextColor.RED));
            return true;
        }

        if (target.getUniqueId().equals(reporterUuid)) {
            reporter.sendMessage(Component.text("你不能举报自己。", NamedTextColor.RED));
            return true;
        }

        UUID targetUuid = target.getUniqueId();

        // 检查被举报玩家冷却
        Long lastReported = reportedCooldown.get(targetUuid);
        if (lastReported != null && (now - lastReported) < REPORTED_COOLDOWN_MS) {
            long remaining = (REPORTED_COOLDOWN_MS - (now - lastReported)) / 1000L;
            reporter.sendMessage(Component.text("该玩家刚刚被举报过，请等待 " + remaining + " 秒后再试。", NamedTextColor.YELLOW));
            return true;
        }

        // 记录冷却
        reporterCooldown.put(reporterUuid, now);
        reportedCooldown.put(targetUuid, now);

        // 执行举报检测
        performReportCheck(reporter, target, reason);
        return true;
    }

    /**
     * 执行举报后的快速检测扫描。
     */
    private void performReportCheck(Player reporter, Player target, String reason) {
        PlayerDataManager pdm = plugin.getPlayerDataManager();
        PlayerData targetData = pdm.getPlayerData(target);

        if (targetData == null) {
            reporter.sendMessage(Component.text("无法获取被举报玩家数据，举报无效。", NamedTextColor.RED));
            return;
        }

        int totalVL = targetData.getTotalVL();
        int failedChecks = targetData.getFailedChecksCount();

        // 收集推理数据（如果启用了物理推理）
        double anomalyScore = 0.0;
        double threatScore = 0.0;
        PhysicsInferenceService pis = plugin.getPhysicsInferenceService();
        boolean inferenceEnabled = pis != null && pis.getAModelTrainCount() > 1000;
        if (inferenceEnabled) {
            DualInferenceResult result = pis.getDualInferenceResult(target.getUniqueId());
            if (result != null) {
                anomalyScore = result.anomalyScore();
                threatScore = result.threatFusionScore();
            }
        }

        // 作弊判定逻辑
        boolean isCheating = false;
        StringBuilder evidence = new StringBuilder();

        if (totalVL > VL_THRESHOLD) {
            isCheating = true;
            evidence.append("VL=").append(totalVL).append(" ");
        }
        if (failedChecks >= FAILED_CHECKS_THRESHOLD) {
            isCheating = true;
            evidence.append("失败检测项=").append(failedChecks).append(" ");
        }
        if (inferenceEnabled && anomalyScore > ANOMALY_THRESHOLD) {
            isCheating = true;
            evidence.append("异常度=").append(String.format("%.2f", anomalyScore)).append(" ");
        }
        if (inferenceEnabled && threatScore > ANOMALY_THRESHOLD) {
            isCheating = true;
            evidence.append("威胁度=").append(String.format("%.2f", threatScore)).append(" ");
        }

        // 通知管理员
        notifyAdmins(reporter, target, totalVL, failedChecks, anomalyScore, threatScore, isCheating, evidence.toString().trim());

        if (isCheating) {
            // 作弊 -> 踢出
            String kickReason = "被玩家举报并检测到作弊行为 | " + evidence.toString().trim();
            plugin.getPunishmentManager().kick(target, kickReason, reporter.getName(), "PlayerReport", totalVL);

            reporter.sendMessage(Component.empty());
            reporter.sendMessage(Component.text("===== 举报结果 =====", NamedTextColor.DARK_AQUA));
            reporter.sendMessage(Component.text("被举报玩家: ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
            reporter.sendMessage(Component.text("检测结果: ", NamedTextColor.GRAY)
                .append(Component.text("确认作弊", NamedTextColor.RED)));
            reporter.sendMessage(Component.text("处理结果: ", NamedTextColor.GRAY)
                .append(Component.text("已踢出服务器", NamedTextColor.RED)));
            reporter.sendMessage(Component.text("证据: ", NamedTextColor.GRAY)
                .append(Component.text(evidence.toString().trim(), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("====================", NamedTextColor.DARK_AQUA));

            // 广播给所有在线玩家
            Bukkit.broadcast(Component.text("[举报] ", NamedTextColor.DARK_AQUA)
                .append(Component.text(target.getName(), NamedTextColor.RED))
                .append(Component.text(" 被 ", NamedTextColor.GRAY))
                .append(Component.text(reporter.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" 举报并确认作弊，已踢出服务器。", NamedTextColor.GRAY)));
        } else {
            // 未作弊 -> 举报无效
            reporter.sendMessage(Component.empty());
            reporter.sendMessage(Component.text("===== 举报结果 =====", NamedTextColor.DARK_AQUA));
            reporter.sendMessage(Component.text("被举报玩家: ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
            reporter.sendMessage(Component.text("检测结果: ", NamedTextColor.GRAY)
                .append(Component.text("未检测到作弊", NamedTextColor.GREEN)));
            reporter.sendMessage(Component.text("处理结果: ", NamedTextColor.GRAY)
                .append(Component.text("举报无效", NamedTextColor.GRAY)));
            reporter.sendMessage(Component.text("当前数据: ", NamedTextColor.GRAY)
                .append(Component.text("VL=" + totalVL + " 失败项=" + failedChecks
                    + (inferenceEnabled ? " 异常度=" + String.format("%.2f", anomalyScore) : ""), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("====================", NamedTextColor.DARK_AQUA));
        }
    }

    /**
     * 通知所有在线管理员举报详情。
     */
    private void notifyAdmins(Player reporter, Player target, int totalVL, int failedChecks,
                              double anomalyScore, double threatScore, boolean isCheating, String evidence) {
        Component prefix = Component.text("[举报] ", NamedTextColor.DARK_AQUA);
        Component detail = Component.text(reporter.getName(), NamedTextColor.YELLOW)
            .append(Component.text(" 举报了 ", NamedTextColor.GRAY))
            .append(Component.text(target.getName(), NamedTextColor.WHITE))
            .append(Component.text(" | VL=" + totalVL + " 失败项=" + failedChecks, NamedTextColor.GRAY));

        if (anomalyScore > 0) {
            detail = detail.append(Component.text(" 异常度=" + String.format("%.2f", anomalyScore), NamedTextColor.GRAY));
        }
        if (threatScore > 0) {
            detail = detail.append(Component.text(" 威胁度=" + String.format("%.2f", threatScore), NamedTextColor.GRAY));
        }

        detail = detail.append(Component.text(" | ", NamedTextColor.GRAY))
            .append(Component.text(isCheating ? "确认作弊" : "未检测到作弊",
                isCheating ? NamedTextColor.RED : NamedTextColor.GREEN));

        if (!evidence.isEmpty()) {
            detail = detail.append(Component.text(" | " + evidence, NamedTextColor.YELLOW));
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("ansac.admin") || online.hasPermission("ansac.alerts")) {
                online.sendMessage(prefix.append(detail));
            }
        }

        plugin.getLogger().info("[举报] " + reporter.getName() + " 举报 " + target.getName()
            + " | VL=" + totalVL + " 失败项=" + failedChecks
            + (anomalyScore > 0 ? " 异常度=" + String.format("%.2f", anomalyScore) : "")
            + (threatScore > 0 ? " 威胁度=" + String.format("%.2f", threatScore) : "")
            + " | 结果=" + (isCheating ? "确认作弊" : "未检测到作弊"));
    }
}
