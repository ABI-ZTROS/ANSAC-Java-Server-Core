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
                    sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(Component.text("ANSAC configuration reloaded successfully.", NamedTextColor.GREEN));
                break;

            case "status":
                if (!sender.hasPermission("ansac.command.status")) {
                    sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
                    return true;
                }
                sendStatus(sender);
                break;

            case "info":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /ansac info <player>", NamedTextColor.RED));
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
        sender.sendMessage(Component.text("=== ANSAC Anti-Cheat ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("/ansac reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac status", NamedTextColor.YELLOW)
                .append(Component.text(" - View plugin status", NamedTextColor.GRAY))
        );
        sender.sendMessage(
            Component.text("/ansac info <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - View player data", NamedTextColor.GRAY))
        );
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== ANSAC Status ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("Version: ", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("Active Players: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getPlayerDataManager().getPlayerCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("Checks Enabled: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getCheckManager().getEnabledChecksCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("Server Type: ", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getSchedulerAdapter().isFolia() ? "Folia" : "Paper/Spigot", NamedTextColor.WHITE))
        );

        // Auth module status
        sender.sendMessage(Component.text("--- Auth Module ---", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("Auth Enabled: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getAuthService().isEnabled()), NamedTextColor.WHITE))
        );
        if (plugin.getAuthService().isEnabled()) {
            sender.sendMessage(
                Component.text("Auth Mode: ", NamedTextColor.YELLOW)
                    .append(Component.text("Install-and-Play (bundled)", NamedTextColor.WHITE))
            );
        }
    }

    private void sendPlayerInfo(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null) {
            sender.sendMessage(Component.text("No data found for this player.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("=== Player Info: " + playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("Ping: ", NamedTextColor.YELLOW)
                .append(Component.text(data.getPing() + "ms", NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("VL (Violation Level): ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getTotalVL()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("Checks Failed: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getFailedChecksCount()), NamedTextColor.WHITE))
        );
    }
}
