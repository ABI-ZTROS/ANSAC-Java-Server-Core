package dev.ztros.ansac;

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
}
