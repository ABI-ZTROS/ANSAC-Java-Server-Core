package dev.ztros.ansac.auth.session;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthConfig;
import dev.ztros.ansac.auth.database.AuthDatabase;
import dev.ztros.ansac.util.ServerVersionAdapter;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SessionManager {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ANSACPlugin plugin;
    private final AuthConfig authConfig;
    private final AuthDatabase database;
    private final Map<UUID, LoginSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> authenticatedIps = new ConcurrentHashMap<>();

    public SessionManager(ANSACPlugin plugin, AuthConfig authConfig, AuthDatabase database) {
        this.plugin = plugin;
        this.authConfig = authConfig;
        this.database = database;
        startAutoKickTask();
        startIpCleanupTask();
    }

    public LoginSession createSession(UUID uuid, String playerName, String ip) {
        LoginSession session = new LoginSession(uuid, playerName, ip);
        sessions.put(uuid, session);

        database.isRegistered(uuid).thenAccept(registered -> {
            session.setRegistered(registered);
            if (registered) {
                checkSameIpAutoLogin(session);
            } else {
                sendLoginPrompt(session);
            }
        });

        return session;
    }

    public void removeSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public LoginSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        return session != null && session.isAuthenticated();
    }

    public void markAuthenticated(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        if (session != null) {
            session.markAuthenticated();
            authenticatedIps.put(session.getIp(), System.currentTimeMillis());
            database.updateLogin(uuid, session.getIp());
        }
    }

    private void checkSameIpAutoLogin(LoginSession session) {
        if (session.isAuthenticated()) return;

        Long lastAuthTime = authenticatedIps.get(session.getIp());
        if (lastAuthTime != null) {
            long elapsedMinutes = (System.currentTimeMillis() - lastAuthTime) / (60 * 1000);
            if (elapsedMinutes < authConfig.getSameIpTimeout()) {
                session.markAuthenticated();
                var player = plugin.getServer().getPlayer(session.getUuid());
                if (player != null && player.isOnline()) {
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        player.sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>[<aqua>ANSAC</gray>] <green>Same-IP auto-login successful."
                        ));
                    });
                }
                plugin.getLogger().info("Same-IP auto-login: " + session.getPlayerName() + " from " + session.getIp());
                return;
            }
        }

        sendLoginPrompt(session);
    }

    private void sendLoginPrompt(LoginSession session) {
        var player = plugin.getServer().getPlayer(session.getUuid());
        if (player != null && player.isOnline()) {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.sendMessage(MINI_MESSAGE.deserialize(
                    "<gray>[<aqua>ANSAC</gray>] <yellow>Please login with <white>/login <password><yellow>."
                ));
            });
        }
    }

    private void startAutoKickTask() {
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            if (!authConfig.isAutoKick()) return;

            int timeout = authConfig.getLoginTimeout();
            for (LoginSession session : sessions.values()) {
                if (!session.isAuthenticated() && session.getElapsedSeconds() >= timeout) {
                    var player = plugin.getServer().getPlayer(session.getUuid());
                    if (player != null && player.isOnline()) {
                        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                            ServerVersionAdapter.kickPlayer(player, MINI_MESSAGE.deserialize(
                                "<red>[ANSAC] <gray>Login timed out. Please reconnect."
                            ));
                        });
                        plugin.getLogger().info("Auto-kicked unauthenticated player: " + session.getPlayerName());
                    }
                }
            }
        }, 5L, 5L, TimeUnit.SECONDS);
    }

    private void startIpCleanupTask() {
        long timeoutMillis = authConfig.getSameIpTimeout() * 60 * 1000L;
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            long now = System.currentTimeMillis();
            authenticatedIps.entrySet().removeIf(entry -> (now - entry.getValue()) > timeoutMillis);
        }, 60L, 60L, TimeUnit.SECONDS);
    }

    public void shutdown() {
        sessions.clear();
        authenticatedIps.clear();
    }
}
