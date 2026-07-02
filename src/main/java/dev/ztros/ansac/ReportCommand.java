package dev.ztros.ansac;

import dev.ztros.ansac.checks.violation.ViolationData;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.physics.mlp.DualInferenceResult;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.player.PlayerDataManager;
import dev.ztros.ansac.report.ReportDemoRecorder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家互相举报命令处理器。
 * 所有玩家均可使用，无需 OP 权限。
 * 举报后保存 Demo 快照并对被举报玩家执行全方位检测，
 * 若检测到作弊行为则踢出，否则通知举报人举报无效。
 */
public class ReportCommand implements CommandExecutor {

    private final ANSACPlugin plugin;
    private final ReportDemoRecorder demoRecorder;

    /** VL 阈值：总 VL > 此值视为作弊 */
    private static final int VL_THRESHOLD = 5;
    /** 推理异常度阈值：异常度 > 此值视为作弊 */
    private static final double ANOMALY_THRESHOLD = 0.65;
    /** 失败检测项阈值：失败项数 >= 此值视为作弊 */
    private static final int FAILED_CHECKS_THRESHOLD = 2;
    /** 单项检测 VL 高阈值 */
    private static final int SINGLE_CHECK_HIGH_VL = 10;

    public ReportCommand(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.demoRecorder = new ReportDemoRecorder(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
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

        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            reporter.sendMessage(Component.text("你不能举报自己。", NamedTextColor.RED));
            return true;
        }

        // 执行举报检测
        performReportCheck(reporter, target, reason);
        return true;
    }

    /**
     * 执行举报后的全方位检测扫描。
     */
    private void performReportCheck(Player reporter, Player target, String reason) {
        PlayerDataManager pdm = plugin.getPlayerDataManager();
        PlayerData targetData = pdm.getPlayerData(target);

        if (targetData == null) {
            reporter.sendMessage(Component.text("无法获取被举报玩家数据，举报无效。", NamedTextColor.RED));
            return;
        }

        // ========== 1. 全方位检测：收集所有检测项的详细违规数据 ==========
        List<ViolationDetail> violationDetails = new ArrayList<>();
        Map<String, ViolationData> violations = targetData.getViolationsView();
        int totalVL = 0;
        int highVLChecks = 0;

        for (Map.Entry<String, ViolationData> entry : violations.entrySet()) {
            ViolationData vd = entry.getValue();
            int vl = vd.getTotalVL();
            if (vl > 0) {
                totalVL += vl;
                violationDetails.add(new ViolationDetail(vd.getCheckName(), vl, vd.getHighestSeverity()));
                if (vl >= SINGLE_CHECK_HIGH_VL) {
                    highVLChecks++;
                }
            }
        }

        int failedChecks = violationDetails.size();

        // ========== 2. 收集推理数据 ==========
        double anomalyScore = 0.0;
        double threatScore = 0.0;
        DualInferenceResult inference = null;
        PhysicsInferenceService pis = plugin.getPhysicsInferenceService();
        boolean inferenceEnabled = pis != null && pis.getAModelTrainCount() > 1000;
        PlayerPhysicsState state = null;

        if (inferenceEnabled) {
            inference = pis.getDualInferenceResult(target.getUniqueId());
            state = pis.getState(target.getUniqueId());
            if (inference != null) {
                anomalyScore = inference.normalAnomalyScore();
                threatScore = inference.threatFusionScore();
            }
        }

        // ========== 3. 保存 Demo 快照 ==========
        String demoFile = demoRecorder.saveDemo(reporter, target, reason, targetData, state, inference);

        // ========== 4. 作弊判定逻辑（全方位） ==========
        boolean isCheating = false;
        StringBuilder evidence = new StringBuilder();
        List<String> evidenceList = new ArrayList<>();

        // 4.1 总VL判定
        if (totalVL > VL_THRESHOLD) {
            isCheating = true;
            evidenceList.add("总VL=" + totalVL);
        }

        // 4.2 失败检测项数判定
        if (failedChecks >= FAILED_CHECKS_THRESHOLD) {
            isCheating = true;
            evidenceList.add("触发检测项=" + failedChecks);
        }

        // 4.3 单项高VL判定（任意检测项VL>=10）
        if (highVLChecks > 0) {
            isCheating = true;
            evidenceList.add("高VL检测项=" + highVLChecks);
        }

        // 4.4 推理异常度判定
        if (inferenceEnabled && anomalyScore > ANOMALY_THRESHOLD) {
            isCheating = true;
            evidenceList.add("异常度=" + String.format("%.2f", anomalyScore));
        }

        // 4.5 威胁度判定
        if (inferenceEnabled && threatScore > ANOMALY_THRESHOLD) {
            isCheating = true;
            evidenceList.add("威胁度=" + String.format("%.2f", threatScore));
        }

        // 4.6 模型选择器直接判定
        if (inference != null && inference.shouldConvict() && inference.getConfidence() > 0.5) {
            isCheating = true;
            evidenceList.add("AI定罪(置信度=" + String.format("%.2f", inference.getConfidence()) + ")");
        }

        for (String e : evidenceList) {
            if (evidence.length() > 0) evidence.append(" | ");
            evidence.append(e);
        }

        // ========== 5. 通知管理员（含全方位检测详情） ==========
        notifyAdmins(reporter, target, totalVL, failedChecks, highVLChecks,
                     anomalyScore, threatScore, isCheating, evidence.toString(),
                     violationDetails, demoFile);

        // ========== 6. 处理结果 ==========
        if (isCheating) {
            // 作弊 -> 踢出
            String kickReason = "被玩家举报并全方位检测到作弊 | " + evidence.toString();
            plugin.getPunishmentManager().kick(target, kickReason, reporter.getName(), "PlayerReport", totalVL);

            reporter.sendMessage(Component.empty());
            reporter.sendMessage(Component.text("===== 举报结果 =====", NamedTextColor.DARK_AQUA));
            reporter.sendMessage(Component.text("被举报玩家: ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
            reporter.sendMessage(Component.text("检测模式: ", NamedTextColor.GRAY)
                .append(Component.text("全方位扫描", NamedTextColor.AQUA)));
            reporter.sendMessage(Component.text("触发检测项: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(failedChecks), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("总违规等级: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(totalVL), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("检测结果: ", NamedTextColor.GRAY)
                .append(Component.text("确认作弊", NamedTextColor.RED)));
            reporter.sendMessage(Component.text("处理结果: ", NamedTextColor.GRAY)
                .append(Component.text("已踢出服务器", NamedTextColor.RED)));
            reporter.sendMessage(Component.text("证据: ", NamedTextColor.GRAY)
                .append(Component.text(evidence.toString(), NamedTextColor.YELLOW)));
            if (demoFile != null) {
                reporter.sendMessage(Component.text("Demo已保存: ", NamedTextColor.GRAY)
                    .append(Component.text(demoFile, NamedTextColor.GREEN)));
            }
            reporter.sendMessage(Component.text("====================", NamedTextColor.DARK_AQUA));

            // 广播
            Bukkit.broadcast(Component.text("[举报] ", NamedTextColor.DARK_AQUA)
                .append(Component.text(target.getName(), NamedTextColor.RED))
                .append(Component.text(" 被 ", NamedTextColor.GRAY))
                .append(Component.text(reporter.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" 举报并经全方位检测确认作弊，已踢出服务器。", NamedTextColor.GRAY)));
        } else {
            // 未作弊 -> 举报无效
            reporter.sendMessage(Component.empty());
            reporter.sendMessage(Component.text("===== 举报结果 =====", NamedTextColor.DARK_AQUA));
            reporter.sendMessage(Component.text("被举报玩家: ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
            reporter.sendMessage(Component.text("检测模式: ", NamedTextColor.GRAY)
                .append(Component.text("全方位扫描", NamedTextColor.AQUA)));
            reporter.sendMessage(Component.text("触发检测项: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(failedChecks), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("总违规等级: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(totalVL), NamedTextColor.YELLOW)));
            reporter.sendMessage(Component.text("检测结果: ", NamedTextColor.GRAY)
                .append(Component.text("未检测到作弊", NamedTextColor.GREEN)));
            reporter.sendMessage(Component.text("处理结果: ", NamedTextColor.GRAY)
                .append(Component.text("举报无效", NamedTextColor.GRAY)));
            if (demoFile != null) {
                reporter.sendMessage(Component.text("Demo已保存: ", NamedTextColor.GRAY)
                    .append(Component.text(demoFile, NamedTextColor.GREEN)));
            }
            reporter.sendMessage(Component.text("====================", NamedTextColor.DARK_AQUA));
        }
    }

    /**
     * 通知所有在线管理员举报详情（含全方位检测数据）。
     */
    private void notifyAdmins(Player reporter, Player target, int totalVL, int failedChecks,
                              int highVLChecks, double anomalyScore, double threatScore,
                              boolean isCheating, String evidence,
                              List<ViolationDetail> details, String demoFile) {
        Component prefix = Component.text("[举报-全方位] ", NamedTextColor.DARK_AQUA);
        Component summary = Component.text(reporter.getName(), NamedTextColor.YELLOW)
            .append(Component.text(" 举报 ", NamedTextColor.GRAY))
            .append(Component.text(target.getName(), NamedTextColor.WHITE))
            .append(Component.text(" | 检测项=" + failedChecks + " 高VL=" + highVLChecks
                + " 总VL=" + totalVL, NamedTextColor.GRAY));

        if (anomalyScore > 0) {
            summary = summary.append(Component.text(" 异常度=" + String.format("%.2f", anomalyScore), NamedTextColor.GRAY));
        }
        if (threatScore > 0) {
            summary = summary.append(Component.text(" 威胁度=" + String.format("%.2f", threatScore), NamedTextColor.GRAY));
        }

        summary = summary.append(Component.text(" | ", NamedTextColor.GRAY))
            .append(Component.text(isCheating ? "确认作弊" : "未检测到作弊",
                isCheating ? NamedTextColor.RED : NamedTextColor.GREEN));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("ansac.admin") || online.hasPermission("ansac.alerts")) {
                online.sendMessage(prefix.append(summary));
                // 发送详细违规数据
                if (!details.isEmpty()) {
                    online.sendMessage(Component.text("  详细违规数据:", NamedTextColor.GRAY));
                    for (ViolationDetail vd : details) {
                        online.sendMessage(Component.text("    - " + vd.checkName + ": VL=" + vd.vl
                            + " 严重度=" + String.format("%.2f", vd.severity), NamedTextColor.YELLOW));
                    }
                }
                if (evidence != null && !evidence.isEmpty()) {
                    online.sendMessage(Component.text("  综合证据: " + evidence, NamedTextColor.GOLD));
                }
                if (demoFile != null) {
                    online.sendMessage(Component.text("  Demo文件: " + demoFile, NamedTextColor.GREEN));
                }
            }
        }

        StringBuilder log = new StringBuilder("[举报-全方位] " + reporter.getName() + " 举报 " + target.getName()
            + " | 检测项=" + failedChecks + " 高VL=" + highVLChecks + " 总VL=" + totalVL);
        if (anomalyScore > 0) log.append(" 异常度=").append(String.format("%.2f", anomalyScore));
        if (threatScore > 0) log.append(" 威胁度=").append(String.format("%.2f", threatScore));
        log.append(" | 结果=").append(isCheating ? "确认作弊" : "未检测到作弊");
        if (evidence != null && !evidence.isEmpty()) log.append(" | 证据=").append(evidence);
        if (demoFile != null) log.append(" | Demo=").append(demoFile);
        plugin.getLogger().info(log.toString());
    }

    /**
     * 违规详情内部类。
     */
    private static class ViolationDetail {
        final String checkName;
        final int vl;
        final double severity;

        ViolationDetail(String checkName, int vl, double severity) {
            this.checkName = checkName;
            this.vl = vl;
            this.severity = severity;
        }
    }
}
