package dev.ztros.ansac;

import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ANSACCommand implements CommandExecutor {

    private final ANSACPlugin plugin;

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
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                plugin.reload();
                sender.sendMessage(ChatColor.GREEN + "ANSAC configuration reloaded successfully.");
                break;

            case "status":
                if (!sender.hasPermission("ansac.command.status")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                sendStatus(sender);
                break;

            case "info":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ansac info <player>");
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
        sender.sendMessage(ChatColor.GOLD + "=== ANSAC Anti-Cheat ===");
        sender.sendMessage(ChatColor.YELLOW + "/ansac reload" + ChatColor.GRAY + " - Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/ansac status" + ChatColor.GRAY + " - View plugin status");
        sender.sendMessage(ChatColor.YELLOW + "/ansac info <player>" + ChatColor.GRAY + " - View player data");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ANSAC Status ===");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Active Players: " + ChatColor.WHITE + plugin.getPlayerDataManager().getPlayerCount());
        sender.sendMessage(ChatColor.YELLOW + "Checks Enabled: " + ChatColor.WHITE + plugin.getCheckManager().getEnabledChecksCount());
        sender.sendMessage(ChatColor.YELLOW + "Server Type: " + ChatColor.WHITE + (plugin.getSchedulerAdapter().isFolia() ? "Folia" : "Paper/Spigot"));
    }

    private void sendPlayerInfo(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "No data found for this player.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Player Info: " + playerName + " ===");
        sender.sendMessage(ChatColor.YELLOW + "Ping: " + ChatColor.WHITE + data.getPing() + "ms");
        sender.sendMessage(ChatColor.YELLOW + "VL (Violation Level): " + ChatColor.WHITE + data.getTotalVL());
        sender.sendMessage(ChatColor.YELLOW + "Checks Failed: " + ChatColor.WHITE + data.getFailedChecksCount());
    }
}
