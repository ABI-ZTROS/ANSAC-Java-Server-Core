package dev.ztros.ansac.checks;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.player.PlayerData;
import lombok.Getter;
import org.bukkit.entity.Player;

/**
 * Base class for all anti-cheat checks.
 * Each check monitors a specific type of cheat behavior.
 */
public abstract class Check {

    @Getter
    protected final ANSACPlugin plugin;

    @Getter
    protected final String name;

    @Getter
    protected final String type;

    @Getter
    protected boolean enabled;

    @Getter
    protected boolean punishable;

    @Getter
    protected int maxVL;

    @Getter
    protected double setbackThreshold;

    @Getter
    protected double alertThreshold;

    public Check(ANSACPlugin plugin, String name, String type) {
        this.plugin = plugin;
        this.name = name;
        this.type = type;
        loadConfig();
    }

    /**
     * Load configuration for this check
     */
    public void loadConfig() {
        String path = "checks." + type.toLowerCase() + "." + name.toLowerCase();
        this.enabled = plugin.getConfig().getBoolean(path + ".enabled", true);
        this.punishable = plugin.getConfig().getBoolean(path + ".punishable", true);
        this.maxVL = plugin.getConfig().getInt(path + ".max-vl", 20);
        this.setbackThreshold = plugin.getConfig().getDouble(path + ".setback-threshold", 5.0);
        this.alertThreshold = plugin.getConfig().getDouble(path + ".alert-threshold", 1.0);
    }

    /**
     * Process a player - called periodically or on events
     */
    public abstract void process(Player player, PlayerData data);

    /**
     * Handle a violation detected by this check
     */
    protected void flag(Player player, PlayerData data, double severity, String details) {
        if (!enabled || data.hasBypass()) return;

        data.addViolation(name, severity);
        int vl = data.getViolation(name).getTotalVL();

        // Alert if above threshold
        if (vl >= alertThreshold) {
            alert(player, vl, details);
        }

        // Setback if above threshold
        if (vl >= setbackThreshold) {
            setback(player, data);
        }

        // Punish if max VL reached
        if (punishable && vl >= maxVL) {
            punish(player, data, vl);
        }
    }

    /**
     * Send alert to staff
     */
    protected void alert(Player player, int vl, String details) {
        String message = String.format(
            "§7[§cANSAC§7] §e%s §7failed §c%s §7(VL: §f%d§7) §8| §7%s",
            player.getName(), name, vl, details
        );

        plugin.getSchedulerAdapter().runAsync(() -> {
            plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("ansac.alerts"))
                .forEach(p -> p.sendMessage(message));
        });

        plugin.getLogger().info("[ALERT] " + player.getName() + " failed " + name + " (VL: " + vl + ") - " + details);
    }

    /**
     * Setback player (teleport back to safe position)
     */
    protected void setback(Player player, PlayerData data) {
        if (data.getLastLocation() != null) {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.teleport(data.getLastLocation());
            });
        }
    }

    /**
     * Punish player (kick/ban)
     */
    protected void punish(Player player, PlayerData data, int vl) {
        plugin.getSchedulerAdapter().runNextTick(() -> {
            player.kickPlayer("§c[ANSAC] §7You have been detected using cheats.\n§7Check: §f" + name + "\n§7VL: §f" + vl);
        });

        plugin.getLogger().warning("[PUNISH] " + player.getName() + " was kicked for " + name + " (VL: " + vl + ")");
    }

    /**
     * Check if player is on ground (safe method)
     */
    protected boolean isOnGround(Player player) {
        return player.isOnGround();
    }

    /**
     * Get player's ping
     */
    protected int getPing(Player player) {
        return player.getPing();
    }
}
