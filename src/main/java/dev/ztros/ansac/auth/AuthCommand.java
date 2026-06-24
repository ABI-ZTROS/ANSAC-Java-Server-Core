package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class AuthCommand implements CommandExecutor {

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthCommand(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        plugin.getLogger().info("[AuthCommand] Command received: " + command.getName() + " from " + sender.getName() + " with args: " + String.join(" ", args));

        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>[ANSAC] This command can only be used by players."));
            return true;
        }

        if (!authService.isEnabled()) {
            player.sendMessage(miniMessage.deserialize("<gray>[<aqua>ANSAC</gray>] <yellow>Authentication is disabled on this server."));
            plugin.getLogger().info("[AuthCommand] Auth is disabled, rejecting command.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "login", "l" -> handleLogin(player, args);
            case "register", "reg" -> handleRegister(player, args);
            case "changepassword", "changepwd" -> handleChangePassword(player, args);
            case "logout" -> handleLogout(player);
            default -> {
                player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Unknown command."));
                plugin.getLogger().warning("[AuthCommand] Unknown command: " + command.getName());
            }
        }

        return true;
    }

    private void handleLogin(Player player, String[] args) {
        plugin.getLogger().info("[AuthCommand] handleLogin called for " + player.getName());
        if (args.length < 1) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /login <password>"));
            return;
        }

        String password = args[0];
        authService.login(player.getUniqueId(), password)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> {
                    plugin.getLogger().info("[AuthCommand] Login result for " + player.getName() + ": " + message);
                    sendMessageSync(player, message);
                })
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>Login request timed out or failed. Try again.");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Login command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleRegister(Player player, String[] args) {
        plugin.getLogger().info("[AuthCommand] handleRegister called for " + player.getName());
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /register <password> <confirm>"));
            return;
        }

        String password = args[0];
        String confirm = args[1];
        authService.register(player.getUniqueId(), player.getName(), password, confirm)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> {
                    plugin.getLogger().info("[AuthCommand] Register result for " + player.getName() + ": " + message);
                    sendMessageSync(player, message);
                })
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>Registration request timed out or failed. Try again.");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Register command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /changepassword <old> <new>"));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];
        authService.changePassword(player.getUniqueId(), oldPassword, newPassword)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> sendMessageSync(player, message))
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>Password change request timed out or failed. Try again.");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] ChangePassword command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleLogout(Player player) {
        authService.logout(player.getUniqueId())
                .thenAccept(message -> sendMessageSync(player, message))
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>Logout failed. Try again.");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Logout command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void sendMessageSync(Player player, String message) {
        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(miniMessage.deserialize(message));
                plugin.getLogger().info("[AuthCommand] Message sent to " + player.getName() + ": " + message);
            } else {
                plugin.getLogger().warning("[AuthCommand] Player " + player.getName() + " is offline, message not sent.");
            }
        });
    }
}
