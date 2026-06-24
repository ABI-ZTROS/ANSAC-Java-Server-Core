package dev.ztros.ansac;

import dev.ztros.ansac.player.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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
}
