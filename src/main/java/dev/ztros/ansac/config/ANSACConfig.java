package dev.ztros.ansac.config;

import dev.ztros.ansac.ANSACPlugin;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Configuration manager for ANSAC.
 */
public class ANSACConfig {

    private final ANSACPlugin plugin;

    @Getter
    private boolean debug;

    @Getter
    private boolean alertsEnabled;

    @Getter
    private boolean punishmentsEnabled;

    @Getter
    private int violationDecayInterval;

    @Getter
    private double violationDecayFactor;

    @Getter
    private int pingCheckInterval;

    public ANSACConfig(ANSACPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();

        this.debug = config.getBoolean("settings.debug", false);
        this.alertsEnabled = config.getBoolean("settings.alerts-enabled", true);
        this.punishmentsEnabled = config.getBoolean("settings.punishments-enabled", true);
        this.violationDecayInterval = config.getInt("settings.violation-decay-interval", 30);
        this.violationDecayFactor = config.getDouble("settings.violation-decay-factor", 0.9);
        this.pingCheckInterval = config.getInt("settings.ping-check-interval", 5);
    }
}
