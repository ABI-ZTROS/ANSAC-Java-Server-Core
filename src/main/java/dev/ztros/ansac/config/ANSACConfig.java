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

    // Physics inference settings
    @Getter
    private boolean physicsInferenceEnabled;
    @Getter
    private boolean physicsPreferInference;
    @Getter
    private boolean physicsAutoLearn;
    @Getter
    private double physicsLearningRate;
    @Getter
    private double physicsInfluenceWeight;
    @Getter
    private int physicsMinSamples;
    @Getter
    private int physicsCalibrationThreshold;
    @Getter
    private double physicsDeviationThreshold;
    @Getter
    private int physicsSaveIntervalMinutes;

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

        // Physics inference
        this.physicsInferenceEnabled = config.getBoolean("physics-inference.enabled", true);
        this.physicsPreferInference = config.getBoolean("physics-inference.prefer-inference", true);
        this.physicsAutoLearn = config.getBoolean("physics-inference.auto-learn", true);
        this.physicsLearningRate = config.getDouble("physics-inference.learning-rate", 0.1);
        this.physicsInfluenceWeight = config.getDouble("physics-inference.influence-weight", 1.0);
        this.physicsMinSamples = config.getInt("physics-inference.min-samples", 10);
        this.physicsCalibrationThreshold = config.getInt("physics-inference.calibration-threshold", 1000);
        this.physicsDeviationThreshold = config.getDouble("physics-inference.deviation-threshold", 0.15);
        this.physicsSaveIntervalMinutes = config.getInt("physics-inference.save-interval-minutes", 30);
    }
}
