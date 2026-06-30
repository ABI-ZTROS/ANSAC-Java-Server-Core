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
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.InferenceScoreboardManager;
import dev.ztros.ansac.punishment.PunishmentManager;
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

    @Getter
    private PunishmentManager punishmentManager;

    @Getter
    private PhysicsInferenceService physicsInferenceService;

    @Getter
    private InferenceScoreboardManager inferenceScoreboardManager;

    @Override
    public void onLoad() {
        // PacketEvents is bundled (shaded), so we must create and set the API instance ourselves
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        getLogger().info("PacketEvents 已加载（内嵌模式）。");
    }

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("========================================");
        getLogger().info("  ANSAC - 高级网络安全反作弊系统");
        getLogger().info("  专为 Folia 设计的反作弊系统");
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
            getLogger().info("认证模块已启用。");
        } else {
            getLogger().info("认证模块已关闭。");
        }

        // Initialize managers
        this.playerDataManager = new PlayerDataManager(this);
        this.checkManager = new CheckManager(this);
        this.punishmentManager = new PunishmentManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Initialize PacketEvents (load was already called in onLoad)
        PacketEvents.getAPI().init();
        new PacketListener(this).register();
        getLogger().info("PacketEvents 集成已启用。");

        // Register commands
        getCommand("ansac").setExecutor(new ANSACCommand(this));
        getCommand("ansac").setTabCompleter(new ANSACTabCompleter());

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

        // Initialize physics inference service
        this.physicsInferenceService = new PhysicsInferenceService(this);
        physicsInferenceService.loadBaseline();
        syncPhysicsInferenceConfig();

        // Initialize inference scoreboard manager
        this.inferenceScoreboardManager = new InferenceScoreboardManager(this);
        inferenceScoreboardManager.start();
        getLogger().info("推理分数板管理器已启动。");

        // Start periodic baseline auto-save
        int saveInterval = ansacConfig.getPhysicsSaveIntervalMinutes();
        if (saveInterval > 0) {
            schedulerAdapter.runTimerAsync(() -> {
                if (physicsInferenceService != null) {
                    physicsInferenceService.saveBaseline();
                }
            }, saveInterval, saveInterval, java.util.concurrent.TimeUnit.MINUTES);
        }

        getLogger().info("ANSAC 已成功启动！");
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

        if (punishmentManager != null) {
            punishmentManager.save();
        }

        if (playerDataManager != null) {
            playerDataManager.shutdown();
        }

        if (checkManager != null) {
            checkManager.shutdown();
        }

        if (physicsInferenceService != null) {
            physicsInferenceService.saveBaseline();
            physicsInferenceService.shutdown();
        }

        if (inferenceScoreboardManager != null) {
            inferenceScoreboardManager.shutdown();
        }

        getLogger().info("ANSAC 已关闭。");
    }

    public void reload() {
        reloadConfig();
        ansacConfig.load();
        checkManager.reload();
        if (punishmentManager != null) {
            punishmentManager.loadConfig();
        }
        if (authService != null) {
            authService.reload();
        }
        if (physicsInferenceService != null) {
            syncPhysicsInferenceConfig();
        }
        getLogger().info("ANSAC 配置已重载。");
    }

    /**
     * 同步物理推理引擎配置。
     */
    private void syncPhysicsInferenceConfig() {
        if (physicsInferenceService == null || ansacConfig == null) return;
        physicsInferenceService.setEnabled(ansacConfig.isPhysicsInferenceEnabled());
        physicsInferenceService.setPreferInference(ansacConfig.isPhysicsPreferInference());
        physicsInferenceService.setAutoLearn(ansacConfig.isPhysicsAutoLearn());
        physicsInferenceService.setLearningRate(ansacConfig.getPhysicsLearningRate());
        physicsInferenceService.setInfluenceWeight(ansacConfig.getPhysicsInfluenceWeight());
        physicsInferenceService.setMinSamples(ansacConfig.getPhysicsMinSamples());
    }
}
