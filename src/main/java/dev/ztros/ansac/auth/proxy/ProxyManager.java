package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class ProxyManager {

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private PluginMessageListener activeHandler;

    public ProxyManager(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;

        String proxyType = authService.getAuthConfig().getProxyType();
        switch (proxyType.toLowerCase()) {
            case "bungeecord" -> initBungeeCord();
            case "velocity" -> initVelocity();
            default -> autoDetect();
        }
    }

    private void autoDetect() {
        // Folia does not expose spigot() API, so we cannot detect BungeeCord from config.
        // Users should explicitly set proxy.type to "bungeecord" or "velocity" in config.yml.
        plugin.getLogger().info("No proxy auto-detected. Set 'auth.proxy.type' explicitly in config.yml.");
    }

    private void initBungeeCord() {
        BungeeCordHandler handler = new BungeeCordHandler(plugin, authService);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "BungeeCord", handler);
        this.activeHandler = handler;
        plugin.getLogger().info("BungeeCord communication enabled.");
    }

    private void initVelocity() {
        VelocityHandler handler = new VelocityHandler(plugin, authService);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "velocity:main", handler);
        this.activeHandler = handler;
        plugin.getLogger().info("Velocity communication enabled.");
    }

    public void sendLoginStatus(String playerName, boolean authenticated) {
        if (activeHandler instanceof BungeeCordHandler bc) {
            bc.sendLoginStatus(playerName, authenticated);
        } else if (activeHandler instanceof VelocityHandler vel) {
            vel.sendLoginStatus(playerName, authenticated);
        }
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "velocity:main");
    }
}
