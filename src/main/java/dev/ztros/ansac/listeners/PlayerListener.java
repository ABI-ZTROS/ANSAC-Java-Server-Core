package dev.ztros.ansac.listeners;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Bukkit event listener for player-related events.
 * Uses Folia-safe event handling.
 */
public class PlayerListener implements Listener {

    private final ANSACPlugin plugin;

    public PlayerListener(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getSchedulerAdapter().runNextTick(() -> {
            plugin.getPlayerDataManager().createPlayerData(event.getPlayer());
            plugin.getLogger().info("Tracking player: " + event.getPlayer().getName());

            // Notify auth service
            if (plugin.getAuthService().isEnabled()) {
                String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
                plugin.getAuthService().handlePlayerJoin(
                    event.getPlayer().getUniqueId(),
                    event.getPlayer().getName(),
                    ip
                );
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getSchedulerAdapter().runNextTick(() -> {
            plugin.getPlayerDataManager().removePlayerData(event.getPlayer());

            // Notify auth service
            if (plugin.getAuthService().isEnabled()) {
                plugin.getAuthService().handlePlayerQuit(event.getPlayer().getUniqueId());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        // Only process if actually moved
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(event.getPlayer());
        if (data == null) return;

        // Update location data
        data.updateLocation(to);

        // Process movement checks
        plugin.getCheckManager().processPlayer(event.getPlayer());
    }
}
