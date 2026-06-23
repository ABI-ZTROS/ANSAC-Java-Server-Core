package dev.ztros.ansac;

import com.tcoded.folialib.FoliaLib;
import dev.ztros.ansac.checks.CheckManager;
import dev.ztros.ansac.config.ANSACConfig;
import dev.ztros.ansac.listeners.PacketListener;
import dev.ztros.ansac.listeners.PlayerListener;
import dev.ztros.ansac.player.PlayerDataManager;
import dev.ztros.ansac.scheduler.SchedulerAdapter;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public class ANSACPlugin extends JavaPlugin {

    @Getter
    private static ANSACPlugin instance;

    @Getter
    private FoliaLib foliaLib;

    @Getter
    private SchedulerAdapter schedulerAdapter;

    @Getter
    private PlayerDataManager playerDataManager;

    @Getter
    private CheckManager checkManager;

    @Getter
    private ANSACConfig ansacConfig;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("========================================");
        getLogger().info("  ANSAC - Advanced Network Security");
        getLogger().info("  Anti-Cheat System for Folia");
        getLogger().info("  Version: " + getDescription().getVersion());
        getLogger().info("========================================");

        // Initialize FoliaLib for cross-platform compatibility
        this.foliaLib = new FoliaLib(this);
        this.schedulerAdapter = new SchedulerAdapter(this);

        // Load configuration
        saveDefaultConfig();
        this.ansacConfig = new ANSACConfig(this);

        // Initialize managers
        this.playerDataManager = new PlayerDataManager(this);
        this.checkManager = new CheckManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register packet listener (PacketEvents is now bundled)
        new PacketListener(this).register();
        getLogger().info("PacketEvents integration enabled.");

        // Register commands
        getCommand("ansac").setExecutor(new ANSACCommand(this));

        getLogger().info("ANSAC has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.shutdown();
        }

        if (checkManager != null) {
            checkManager.shutdown();
        }

        getLogger().info("ANSAC has been disabled.");
    }

    public void reload() {
        reloadConfig();
        ansacConfig.load();
        checkManager.reload();
        getLogger().info("ANSAC configuration reloaded.");
    }
}
