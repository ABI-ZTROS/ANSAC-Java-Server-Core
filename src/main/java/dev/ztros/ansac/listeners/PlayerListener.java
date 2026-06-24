package dev.ztros.ansac.listeners;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

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
            plugin.getLogger().info("开始追踪玩家：" + event.getPlayer().getName());

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

        // Update ping sample for latency compensation
        data.getPingCompensator().addPingSample(data.getPing());

        // Detect sudden velocity changes (wind charge, explosion knockback, etc.)
        Vector velocity = event.getPlayer().getVelocity();
        double velLen = velocity.length();
        if (velLen > 1.5) {
            data.setLastKnockbackTime(System.currentTimeMillis());
        }

        // Update location data
        data.updateLocation(to);

        // Process movement checks
        plugin.getCheckManager().processPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;

        // Wind charge damage or explosion = knockback
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL) {
            PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
            if (data != null) {
                data.setLastKnockbackTime(System.currentTimeMillis());
            }
        }
    }
}
