package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.crypto.BCryptHasher;
import dev.ztros.ansac.auth.database.AuthDatabase;
import dev.ztros.ansac.auth.database.SQLiteDatabase;
import dev.ztros.ansac.auth.proxy.ProxyManager;
import dev.ztros.ansac.auth.session.LoginSession;
import dev.ztros.ansac.auth.session.SessionManager;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            plugin.getLogger().info("Authentication module initialized.");
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
            future.complete("<red>[ANSAC] Password must be at least 4 characters.");
            return future;
        }

        if (!password.equals(confirmPassword)) {
            future.complete("<red>[ANSAC] Passwords do not match.");
            return future;
        }

        if (password.length() > 64) {
            future.complete("<red>[ANSAC] Password is too long (max 64 characters).");
            return future;
        }

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found. Please reconnect.");
            return future;
        }

        database.isRegisteredByName(playerName).thenAccept(registered -> {
            if (registered) {
                future.complete("<red>[ANSAC] You are already registered. Use <white>/login <password><red>.");
                return;
            }

            String hash = BCryptHasher.hash(password);
            database.savePassword(uuid, playerName, hash, session.getIp()).thenRun(() -> {
                session.setRegistered(true);
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>Registration successful. You are now logged in.");
            });
        });

        return future;
    }

    public CompletableFuture<String> login(UUID uuid, String password) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found. Please reconnect.");
            return future;
        }

        if (session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] You are already logged in.");
            return future;
        }

        if (!session.isRegistered()) {
            future.complete("<red>[ANSAC] You are not registered. Use <white>/register <password> <confirm><red>.");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] Account data not found. Please contact an admin.");
                return;
            }

            if (BCryptHasher.verify(password, hashOpt.get())) {
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>Login successful. Welcome back!");
            } else {
                session.incrementFailedAttempts();
                int attempts = session.getFailedAttempts();
                if (attempts >= 5) {
                    var player = plugin.getServer().getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                            player.kick(MINI_MESSAGE.deserialize(
                                "<red>[ANSAC] <gray>Too many failed login attempts."
                            ));
                        });
                    }
                    future.complete("<red>[ANSAC] Too many failed login attempts.");
                } else {
                    future.complete("<red>[ANSAC] <gray>Incorrect password. Attempts remaining: <white>" + (5 - attempts));
                }
            }
        });

        return future;
    }

    public CompletableFuture<String> changePassword(UUID uuid, String oldPassword, String newPassword) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (newPassword == null || newPassword.length() < 4) {
            future.complete("<red>[ANSAC] New password must be at least 4 characters.");
            return future;
        }

        if (newPassword.length() > 64) {
            future.complete("<red>[ANSAC] New password is too long (max 64 characters).");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] Account data not found.");
                return;
            }

            if (!BCryptHasher.verify(oldPassword, hashOpt.get())) {
                future.complete("<red>[ANSAC] Old password is incorrect.");
                return;
            }

            String newHash = BCryptHasher.hash(newPassword);
            LoginSession session = sessionManager.getSession(uuid);
            String ip = session != null ? session.getIp() : "unknown";
            String name = session != null ? session.getPlayerName() : "unknown";

            database.savePassword(uuid, name, newHash, ip).thenRun(() -> {
                future.complete("<green>[ANSAC] <gray>Password changed successfully.");
            });
        });

        return future;
    }

    public CompletableFuture<String> logout(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found.");
            return future;
        }

        if (!session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] You are not logged in.");
            return future;
        }

        session.setAuthenticated(false);
        future.complete("<yellow>[ANSAC] <gray>You have been logged out. Use <white>/login <password><gray> to log in again.");
        return future;
    }

    public void reload() {
        authConfig.load();
        plugin.getLogger().info("Auth configuration reloaded.");
    }

    public void shutdown() {
        sessionManager.shutdown();
        database.shutdown().join();
        if (proxyManager != null) {
            proxyManager.shutdown();
        }
        plugin.getLogger().info("Authentication module shut down.");
    }
}
