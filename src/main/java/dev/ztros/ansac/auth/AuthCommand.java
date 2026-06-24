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
        plugin.getLogger().info("[AuthCommand] 收到命令：" + command.getName() + " 来自 " + sender.getName() + " 参数：" + String.join(" ", args));

        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>[ANSAC] 该命令只能由玩家执行。"));
            return true;
        }

        if (!authService.isEnabled()) {
            player.sendMessage(miniMessage.deserialize("<gray>[<aqua>ANSAC</gray>] <yellow>此服务器未启用认证功能。"));
            plugin.getLogger().info("[AuthCommand] 认证功能已关闭，拒绝命令。");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "login", "l" -> handleLogin(player, args);
            case "register", "reg" -> handleRegister(player, args);
            case "changepassword", "changepwd" -> handleChangePassword(player, args);
            case "logout" -> handleLogout(player);
            default -> {
                player.sendMessage(miniMessage.deserialize("<red>[ANSAC] 未知命令。"));
                plugin.getLogger().warning("[AuthCommand] 未知命令：" + command.getName());
            }
        }

        return true;
    }

    private void handleLogin(Player player, String[] args) {
        plugin.getLogger().info("[AuthCommand] 处理登录命令：" + player.getName());
        if (args.length < 1) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] 用法：/login <密码>"));
            return;
        }

        String password = args[0];
        authService.login(player.getUniqueId(), password)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> {
                    plugin.getLogger().info("[AuthCommand] 登录结果 (" + player.getName() + "): " + message);
                    sendMessageSync(player, message);
                })
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>登录请求超时或失败，请重试。");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Login command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleRegister(Player player, String[] args) {
        plugin.getLogger().info("[AuthCommand] 处理注册命令：" + player.getName());
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] 用法：/register <密码> <确认密码>"));
            return;
        }

        String password = args[0];
        String confirm = args[1];
        authService.register(player.getUniqueId(), player.getName(), password, confirm)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> {
                    plugin.getLogger().info("[AuthCommand] 注册结果 (" + player.getName() + "): " + message);
                    sendMessageSync(player, message);
                })
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>注册请求超时或失败，请重试。");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Register command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] 用法：/changepassword <旧密码> <新密码>"));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];
        authService.changePassword(player.getUniqueId(), oldPassword, newPassword)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(message -> sendMessageSync(player, message))
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>密码修改请求超时或失败，请重试。");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] ChangePassword command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void handleLogout(Player player) {
        authService.logout(player.getUniqueId())
                .thenAccept(message -> sendMessageSync(player, message))
                .exceptionally(ex -> {
                    sendMessageSync(player, "<red>[ANSAC] <gray>登出失败，请重试。");
                    plugin.getLogger().log(Level.WARNING, "[AuthCommand] Logout command failed for " + player.getName(), ex);
                    return null;
                });
    }

    private void sendMessageSync(Player player, String message) {
        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(miniMessage.deserialize(message));
                plugin.getLogger().info("[AuthCommand] 消息已发送至 " + player.getName() + "：" + message);
            } else {
                plugin.getLogger().warning("[AuthCommand] 玩家 " + player.getName() + " 不在线，消息未发送。");
            }
        });
    }
}
