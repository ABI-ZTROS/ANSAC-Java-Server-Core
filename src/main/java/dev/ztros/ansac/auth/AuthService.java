package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.crypto.BCryptHasher;
import dev.ztros.ansac.auth.database.AuthDatabase;
import dev.ztros.ansac.auth.database.SQLiteDatabase;
import dev.ztros.ansac.auth.proxy.ProxyManager;
import dev.ztros.ansac.auth.session.LoginSession;
import dev.ztros.ansac.auth.session.SessionManager;
import dev.ztros.ansac.util.ServerVersionAdapter;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public class AuthService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    @Getter
    private final ANSACPlugin plugin;

    @Getter
    private final AuthConfig authConfig;

    private final AuthDatabase database;
    private final SessionManager sessionManager;
    private ProxyManager proxyManager;

    public AuthService(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.authConfig = new AuthConfig(plugin);
        this.database = new SQLiteDatabase(plugin);
        this.sessionManager = new SessionManager(plugin, authConfig, database);

        if (authConfig.isProxyEnabled()) {
            this.proxyManager = new ProxyManager(plugin, this);
        }

        database.initialize().thenRun(() -> {
            plugin.getLogger().info("认证模块已初始化。");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "认证数据库初始化失败！", ex);
            return null;
        });
    }

    public boolean isAuthenticated(UUID uuid) {
        return sessionManager.isAuthenticated(uuid);
    }

    public boolean isEnabled() {
        return authConfig.isEnabled();
    }

    public void handlePlayerJoin(UUID uuid, String playerName, String ip) {
        if (!authConfig.isEnabled()) return;
        sessionManager.createSession(uuid, playerName, ip);
    }

    public void handlePlayerQuit(UUID uuid) {
        if (!authConfig.isEnabled()) return;
        sessionManager.removeSession(uuid);
    }

    public CompletableFuture<String> register(UUID uuid, String playerName, String password, String confirmPassword) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (password == null || password.length() < 4) {
            future.complete("<red>[ANSAC] 密码长度至少为 4 个字符。");
            return future;
        }

        if (!password.equals(confirmPassword)) {
            future.complete("<red>[ANSAC] 两次输入的密码不一致。");
            return future;
        }

        if (password.length() > 64) {
            future.complete("<red>[ANSAC] 密码过长（最多 64 个字符）。");
            return future;
        }

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] 会话不存在，请重新连接。");
            return future;
        }

        database.isRegisteredByName(playerName).thenAccept(registered -> {
            if (registered) {
                future.complete("<red>[ANSAC] 你已注册，请使用 <white>/login <密码><red>。");
                return;
            }

            String hash = BCryptHasher.hash(password);
            database.savePassword(uuid, playerName, hash, session.getIp()).thenRun(() -> {
                session.setRegistered(true);
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>注册成功，已自动登录。");
            }).exceptionally(ex -> {
                future.complete("<red>[ANSAC] <gray>注册失败，数据库异常，请联系管理员。");
                plugin.getLogger().log(Level.SEVERE, "注册信息保存失败：" + playerName, ex);
                return null;
            });
        }).exceptionally(ex -> {
            future.complete("<red>[ANSAC] <gray>注册失败，数据库异常，请联系管理员。");
            plugin.getLogger().log(Level.SEVERE, "注册检查失败：" + playerName, ex);
            return null;
        });

        return future;
    }

    public CompletableFuture<String> login(UUID uuid, String password) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] 会话不存在，请重新连接。");
            return future;
        }

        if (session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] 你已经登录了。");
            return future;
        }

        if (!session.isRegistered()) {
            future.complete("<red>[ANSAC] 你尚未注册，请使用 <white>/register <密码> <确认密码><red>。");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] 账户数据不存在，请联系管理员。");
                return;
            }

            if (BCryptHasher.verify(password, hashOpt.get())) {
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>登录成功，欢迎回来！");
            } else {
                session.incrementFailedAttempts();
                int attempts = session.getFailedAttempts();
                if (attempts >= 5) {
                    var player = plugin.getServer().getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                            ServerVersionAdapter.kickPlayer(player, MINI_MESSAGE.deserialize(
                                "<red>[ANSAC] <gray>登录失败次数过多，已被踢出服务器。"
                            ));
                        });
                    }
                    future.complete("<red>[ANSAC] 登录失败次数过多，已被踢出服务器。");
                } else {
                    future.complete("<red>[ANSAC] <gray>密码错误，剩余尝试次数：<white>" + (5 - attempts));
                }
            }
        }).exceptionally(ex -> {
            future.complete("<red>[ANSAC] <gray>登录失败，数据库异常，请联系管理员。");
            plugin.getLogger().log(Level.SEVERE, "获取密码哈希失败（登录）", ex);
            return null;
        });

        return future;
    }

    public CompletableFuture<String> changePassword(UUID uuid, String oldPassword, String newPassword) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (newPassword == null || newPassword.length() < 4) {
            future.complete("<red>[ANSAC] 新密码长度至少为 4 个字符。");
            return future;
        }

        if (newPassword.length() > 64) {
            future.complete("<red>[ANSAC] 新密码过长（最多 64 个字符）。");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] 账户数据不存在。");
                return;
            }

            if (!BCryptHasher.verify(oldPassword, hashOpt.get())) {
                future.complete("<red>[ANSAC] 原密码错误。");
                return;
            }

            String newHash = BCryptHasher.hash(newPassword);
            LoginSession session = sessionManager.getSession(uuid);
            String ip = session != null ? session.getIp() : "unknown";
            String name = session != null ? session.getPlayerName() : "unknown";

            database.savePassword(uuid, name, newHash, ip).thenRun(() -> {
                future.complete("<green>[ANSAC] <gray>密码修改成功。");
            }).exceptionally(ex -> {
                future.complete("<red>[ANSAC] <gray>密码修改失败，数据库异常。");
                plugin.getLogger().log(Level.SEVERE, "保存新密码失败", ex);
                return null;
            });
        }).exceptionally(ex -> {
            future.complete("<red>[ANSAC] <gray>密码修改失败，数据库异常。");
            plugin.getLogger().log(Level.SEVERE, "获取密码哈希失败（修改密码）", ex);
            return null;
        });

        return future;
    }

    public CompletableFuture<String> logout(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] 会话不存在。");
            return future;
        }

        if (!session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] 你尚未登录。");
            return future;
        }

        session.setAuthenticated(false);
        future.complete("<yellow>[ANSAC] <gray>已登出，使用 <white>/login <密码><gray> 重新登录。");
        return future;
    }

    public void reload() {
        authConfig.load();
        plugin.getLogger().info("认证配置已重载。");
    }

    public void shutdown() {
        sessionManager.shutdown();
        database.shutdown().join();
        if (proxyManager != null) {
            proxyManager.shutdown();
        }
        plugin.getLogger().info("认证模块已关闭。");
    }
}
