package dev.ztros.ansac;

import com.tcoded.folialib.FoliaLib;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import dev.ztros.ansac.auth.AuthCommand;
import dev.ztros.ansac.auth.AuthListener;
import dev.ztros.ansac.auth.AuthService;
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

    @Getter
    private AuthService authService;

    @Override
    public void onLoad() {
        // PacketEvents is bundled (shaded), so we must create and set the API instance ourselves
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        getLogger().info("PacketEvents loaded (shaded mode).");
    }

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

        // Initialize authentication module
        this.authService = new AuthService(this);
        if (authService.isEnabled()) {
            getServer().getPluginManager().registerEvents(
                new AuthListener(this, authService), this
            );
            getLogger().info("Authentication module enabled.");
        } else {
            getLogger().info("Authentication module disabled.");
        }

        // Initialize managers
        this.playerDataManager = new PlayerDataManager(this);
        this.checkManager = new CheckManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Initialize PacketEvents (load was already called in onLoad)
        PacketEvents.getAPI().init();
        new PacketListener(this).register();
        getLogger().info("PacketEvents integration enabled.");

        // Register commands
        getCommand("ansac").setExecutor(new ANSACCommand(this));

        // Register auth commands
        if (authService.isEnabled()) {
            var authCommand = new AuthCommand(this, authService);
            var loginCmd = getCommand("login");
            var registerCmd = getCommand("register");
            var changepwdCmd = getCommand("changepassword");
            var logoutCmd = getCommand("logout");
            if (loginCmd != null) loginCmd.setExecutor(authCommand);
            if (registerCmd != null) registerCmd.setExecutor(authCommand);
            if (changepwdCmd != null) changepwdCmd.setExecutor(authCommand);
            if (logoutCmd != null) logoutCmd.setExecutor(authCommand);
        }

        getLogger().info("ANSAC has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Terminate PacketEvents
        try {
            PacketEvents.getAPI().terminate();
        } catch (Exception e) {
            // Ignore if not initialized
        }

        if (authService != null) {
            authService.shutdown();
        }

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
        if (authService != null) {
            authService.reload();
        }
        getLogger().info("ANSAC configuration reloaded.");
    }
}
