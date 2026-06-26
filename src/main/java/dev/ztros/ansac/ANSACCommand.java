package dev.ztros.ansac;

import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.InferenceResult;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.punishment.PunishmentEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * ANSAC command handler.
 * Uses Adventure Component API (required for Paper/Folia 1.21+).
 */
public class ANSACCommand implements CommandExecutor {

    private final ANSACPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ANSACCommand(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("ansac.command.reload")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(Component.text("ANSAC 配置重载成功。", NamedTextColor.GREEN));
                break;

            case "status":
                if (!sender.hasPermission("ansac.command.status")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                sendStatus(sender);
                break;

            case "info":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac info <玩家名>", NamedTextColor.RED));
                    return true;
                }
                sendPlayerInfo(sender, args[1]);
                break;

            case "ban":
                if (!sender.hasPermission("ansac.command.ban")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleBan(sender, args);
                break;

            case "kick":
                if (!sender.hasPermission("ansac.command.kick")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleKick(sender, args);
                break;

            case "unban":
                if (!sender.hasPermission("ansac.command.unban")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleUnban(sender, args);
                break;

            case "banlist":
                if (!sender.hasPermission("ansac.command.banlist")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleBanlist(sender);
                break;

            case "trust":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac trust <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleTrust(sender, args[1]);
                break;

            case "untrust":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac untrust <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleUntrust(sender, args[1]);
                break;

            case "trustlist":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleTrustlist(sender);
                break;

            case "baseline":
                if (!sender.hasPermission("ansac.command.baseline")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    handleBaselineSub(sender, args[1]);
                } else {
                    handleBaseline(sender);
                }
                break;

            case "inference":
                if (!sender.hasPermission("ansac.command.inference")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    handleInferencePlayer(sender, args[1]);
                } else {
                    handleInferenceStatus(sender);
                }
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== ANSAC 反作弊系统 ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("/ansac reload", NamedTextColor.YELLOW)
                .append(Component.text(" - 重载配置", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac status", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看插件状态", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac info <玩家名>", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看玩家数据", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac ban <玩家> [时长] [原因]", NamedTextColor.YELLOW)
                .append(Component.text(" - 封禁玩家 (forever=永久)", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("  时长: 10s/20s/1min/30min/1h/8h/24h/3d/7d/15d/30d/180d/360d/3600d", NamedTextColor.GRAY)
        );
        sender.sendMessage(
            Component.text("/ansac kick <玩家> [原因]", NamedTextColor.YELLOW)
                .append(Component.text(" - 踢出玩家", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac unban <玩家/UUID>", NamedTextColor.YELLOW)
                .append(Component.text(" - 解封玩家", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac banlist", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看封禁列表", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac trust <玩家>", NamedTextColor.YELLOW)
                .append(Component.text(" - 标记玩家为受信任（数据用于自学习）", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac untrust <玩家>", NamedTextColor.YELLOW)
                .append(Component.text(" - 取消信任玩家", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac trustlist", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看受信任玩家列表", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac baseline [reset|save]", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看/重置/保存基准模型", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac inference [玩家]", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看推理服务状态/玩家物理快照", NamedTextColor.GRAY))
        );
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== ANSAC 状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("版本：", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("在线玩家：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getPlayerDataManager().getPlayerCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("已启用检测：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getCheckManager().getEnabledChecksCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("服务器类型：", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getSchedulerAdapter().isFolia() ? "Folia" : "Paper/Spigot", NamedTextColor.WHITE))
        );

        // Auth module status
        sender.sendMessage(Component.text("--- 认证模块 ---", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("认证状态：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getAuthService().isEnabled()), NamedTextColor.WHITE))
        );
        if (plugin.getAuthService().isEnabled()) {
            sender.sendMessage(
                Component.text("认证模式：", NamedTextColor.YELLOW)
                    .append(Component.text("即装即用", NamedTextColor.WHITE))
            );
        }
    }

    private void sendPlayerInfo(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage(Component.text("找不到该玩家。", NamedTextColor.RED));
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null) {
            sender.sendMessage(Component.text("未找到该玩家的数据。", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("=== 玩家信息：" + playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("延迟：", NamedTextColor.YELLOW)
                .append(Component.text(data.getPing() + "ms", NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("违规等级（VL）：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getTotalVL()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("触发检测次数：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getFailedChecksCount()), NamedTextColor.WHITE))
        );
    }

    // ============================================================
    // Punishment Commands
    // ============================================================

    private void handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac ban <玩家> [时长] [原因]", NamedTextColor.RED));
            sender.sendMessage(Component.text("时长支持: 10s, 20s, 1min, 30min, 1h, 8h, 24h, 3d, 7d, 15d, 30d, 180d, 360d, 3600d, forever", NamedTextColor.GRAY));
            return;
        }

        String targetName = args[1];
        long durationSeconds = -1;
        String reason = "违反服务器规则";

        if (args.length >= 3) {
            long parsed = parseDuration(args[2]);
            if (parsed != Long.MIN_VALUE) {
                durationSeconds = parsed;
                if (args.length >= 4) {
                    reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                }
            } else {
                // Third arg is part of reason, not duration
                durationSeconds = -1;
                reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            }
        }

        String operator = sender instanceof Player ? sender.getName() : "CONSOLE";

        String durationLabel = durationSeconds >= 0 ? formatDurationLabel(durationSeconds) : "永久";
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            plugin.getPunishmentManager().ban(target, reason, durationSeconds, operator, null, 0);
            sender.sendMessage(Component.text("已封禁玩家 " + targetName
                + " (" + durationLabel + ")"
                + " | 原因: " + reason, NamedTextColor.GREEN));
        } else {
            plugin.getPunishmentManager().banOffline(targetName, reason, durationSeconds, operator);
            sender.sendMessage(Component.text("已封禁离线玩家 " + targetName
                + " (" + durationLabel + ")"
                + " | 原因: " + reason, NamedTextColor.GREEN));
        }
    }

    /**
     * Parse duration string to seconds.
     * Supports: 10s, 20s, 1min, 30min, 1h, 8h, 24h, 3d, 7d, 15d, 30d, 180d, 360d, 3600d, forever
     * Also supports raw numbers (treated as minutes for backward compatibility).
     * Returns Long.MIN_VALUE if parsing fails.
     */
    private long parseDuration(String input) {
        String s = input.trim().toLowerCase();
        if (s.equals("forever") || s.equals("perm") || s.equals("permanent")) {
            return -1;
        }
        // Number + unit
        if (s.matches("\\d+s")) {
            return Long.parseLong(s.substring(0, s.length() - 1));
        }
        if (s.matches("\\d+min")) {
            return Long.parseLong(s.substring(0, s.length() - 3)) * 60;
        }
        if (s.matches("\\d+h")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 3600;
        }
        if (s.matches("\\d+d")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 86400;
        }
        // Raw number: treat as minutes for backward compatibility
        try {
            return Long.parseLong(s) * 60;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private String formatDurationLabel(long seconds) {
        if (seconds < 0) return "永久";
        if (seconds < 60) return seconds + "秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时";
        long days = hours / 24;
        return days + "天";
    }

    private void handleKick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac kick <玩家> [原因]", NamedTextColor.RED));
            return;
        }

        String targetName = args[1];
        String reason = args.length >= 3
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
            : "违反服务器规则";

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }

        String operator = sender instanceof Player ? sender.getName() : "CONSOLE";
        plugin.getPunishmentManager().kick(target, reason, operator, null, 0);
        sender.sendMessage(Component.text("已踢出玩家 " + targetName + " | 原因: " + reason, NamedTextColor.GREEN));
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac unban <玩家/UUID>", NamedTextColor.RED));
            return;
        }

        String identifier = args[1];
        boolean success = plugin.getPunishmentManager().unban(identifier);
        if (success) {
            sender.sendMessage(Component.text("已成功解封 " + identifier, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("未找到 " + identifier + " 的封禁记录。", NamedTextColor.RED));
        }
    }

    private void handleBanlist(CommandSender sender) {
        var bans = plugin.getPunishmentManager().getActiveBans();
        if (bans.isEmpty()) {
            sender.sendMessage(Component.text("当前没有活跃的封禁记录。", NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.text("=== 活跃封禁列表 (" + bans.size() + "条) ===", NamedTextColor.GOLD));
        for (PunishmentEntry entry : bans) {
            String info = entry.getPlayerName()
                + " | 原因: " + entry.getReason()
                + " | 操作者: " + entry.getOperator()
                + " | 时长: " + (entry.isPermanent() ? "永久" : formatDurationLabel(entry.getDurationSeconds()))
                + " | 剩余: " + (entry.isPermanent() ? "永久" : formatRemaining(entry));
            sender.sendMessage(Component.text(info, NamedTextColor.YELLOW));
        }
    }

    private String formatRemaining(PunishmentEntry entry) {
        long remainingMs = entry.getExpiryTime() - System.currentTimeMillis();
        if (remainingMs <= 0) return "已过期";
        long seconds = remainingMs / 1000L;
        if (seconds < 60) return seconds + "秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时" + (minutes % 60) + "分钟";
        return (hours / 24) + "天" + ((hours % 24) > 0 ? (hours % 24) + "小时" : "");
    }

    // ============================================================
    // Physics Inference Commands
    // ============================================================

    private void handleTrust(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        svc.markPlayerTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("已将玩家 " + playerName + " 标记为受信任，其移动数据将用于自学习。", NamedTextColor.GREEN));
    }

    private void handleUntrust(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        svc.unmarkPlayerTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("已取消玩家 " + playerName + " 的信任标记。", NamedTextColor.GREEN));
    }

    private void handleTrustlist(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        var trusted = svc.getTrustedPlayers();
        if (trusted.isEmpty()) {
            sender.sendMessage(Component.text("当前没有受信任的玩家。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 受信任玩家列表 (" + trusted.size() + "人) ===", NamedTextColor.GOLD));
        for (java.util.UUID uuid : trusted.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString().substring(0, 8);
            sender.sendMessage(Component.text("- " + name, NamedTextColor.YELLOW));
        }
    }

    private void handleBaseline(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("=== 基准模型状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("场景数量：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getScenarioCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("总采样数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getBaselineModel().getTotalSamples()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习进度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.1f%%", svc.getLearningProgressPercent()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("受信任玩家：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getTrustedPlayerCount()), NamedTextColor.WHITE)));
    }

    private void handleBaselineSub(CommandSender sender, String sub) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        switch (sub.toLowerCase()) {
            case "reset":
                svc.getBaselineModel().reset();
                sender.sendMessage(Component.text("基准模型已重置，所有学习数据已清空。", NamedTextColor.GREEN));
                break;
            case "save":
                svc.saveBaseline();
                sender.sendMessage(Component.text("基准模型已保存。", NamedTextColor.GREEN));
                break;
            default:
                sender.sendMessage(Component.text("未知子命令。用法: /ansac baseline [reset|save]", NamedTextColor.RED));
                break;
        }
    }

    private void handleInferenceStatus(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("=== 物理推理服务状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("服务状态：", NamedTextColor.YELLOW)
            .append(Component.text(svc.isEnabled() ? "启用" : "禁用", svc.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("优先推理：", NamedTextColor.YELLOW)
            .append(Component.text(svc.isPreferInference() ? "是" : "否", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("自动学习：", NamedTextColor.YELLOW)
            .append(Component.text(svc.isAutoLearn() ? "是" : "否", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("受信任玩家：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getTrustedPlayerCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getLearningCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("修正次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getCorrectionCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("迭代次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getIterationCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习进度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.1f%%", svc.getLearningProgressPercent()), NamedTextColor.WHITE)));
    }

    private void handleInferencePlayer(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        InferenceResult result = svc.getInferenceResult(target.getUniqueId());
        if (result == InferenceResult.EMPTY) {
            sender.sendMessage(Component.text("该玩家暂无推理数据（可能刚上线或尚未移动）。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 玩家物理快照：" + playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("水平速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.horizontalSpeed()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Y轴速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.velocityY()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("预测Y速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.predictedVelocityY()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("预期最大水平速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.expectedMaxHorizontalSpeed()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("速度偏差比：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.getSpeedDeviationRatio()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("垂直偏差：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.getVerticalDeviation()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("跳跃阶段：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(result.jumpPhase()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("空中状态：", NamedTextColor.YELLOW)
            .append(Component.text(result.inAir() ? "是" : "否", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("跌落距离：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.2f", result.fallDistance()), NamedTextColor.WHITE)));
        boolean isTrusted = svc.isTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("信任状态：", NamedTextColor.YELLOW)
            .append(Component.text(isTrusted ? "受信任" : "未信任", isTrusted ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
    }
}
