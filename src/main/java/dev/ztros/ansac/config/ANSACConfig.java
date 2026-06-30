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

    @Getter
    private boolean mlpEnabled;
    @Getter
    private int mlpSamplingTarget;

    // MLP 网络参数（从 mlp-network 配置段读取）
    @Getter private int movementHidden1;
    @Getter private int movementHidden2;
    @Getter private double movementLearningRate;
    @Getter private int combatHidden1;
    @Getter private int combatHidden2;
    @Getter private double combatLearningRate;
    @Getter private int fusionHidden1;
    @Getter private double fusionLearningRate;
    @Getter private double gradClip;
    @Getter private double weightClip;
    @Getter private int trainEpochs;
    @Getter private int modelPunishVL;
    @Getter private double anomalyThreshold;

    // 双模型 AB 架构配置
    @Getter private boolean dualModelEnabled;
    @Getter private double dualModelAWeight;
    @Getter private double dualModelBWeight;
    @Getter private double dualModelRuleWeight;
    @Getter private double dualConfirmThreshold;
    @Getter private double singleConvictThreshold;
    @Getter private double highRiskBWeightBoost;
    @Getter private int threatSamplingTarget;
    @Getter private boolean realtimeOnlineLearning;

    // 威胁模型网络参数 (B模型)
    @Getter private int threatMovementHidden1;
    @Getter private int threatMovementHidden2;
    @Getter private double threatMovementLearningRate;
    @Getter private int threatCombatHidden1;
    @Getter private int threatCombatHidden2;
    @Getter private double threatCombatLearningRate;
    @Getter private int threatFusionHidden1;
    @Getter private double threatFusionLearningRate;

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
        this.mlpEnabled = config.getBoolean("physics-inference.mlp-enabled", true);
        this.mlpSamplingTarget = config.getInt("physics-inference.mlp-sampling-target", 5000);

        // MLP 网络参数
        this.movementHidden1 = clampInt(config.getInt("physics-inference.mlp-network.movement.hidden1", 56), 4, 256);
        this.movementHidden2 = clampInt(config.getInt("physics-inference.mlp-network.movement.hidden2", 32), 4, 256);
        this.movementLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.movement.learning-rate", 0.01), 0.0001, 0.5);
        this.combatHidden1 = clampInt(config.getInt("physics-inference.mlp-network.combat.hidden1", 16), 4, 256);
        this.combatHidden2 = clampInt(config.getInt("physics-inference.mlp-network.combat.hidden2", 8), 4, 256);
        this.combatLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.combat.learning-rate", 0.01), 0.0001, 0.5);
        this.fusionHidden1 = clampInt(config.getInt("physics-inference.mlp-network.fusion.hidden1", 12), 4, 64);
        this.fusionLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.fusion.learning-rate", 0.01), 0.0001, 0.5);
        this.gradClip = clampDouble(config.getDouble("physics-inference.mlp-network.grad-clip", 5.0), 0.1, 100.0);
        this.weightClip = clampDouble(config.getDouble("physics-inference.mlp-network.weight-clip", 50.0), 1.0, 1000.0);
        this.trainEpochs = clampInt(config.getInt("physics-inference.mlp-network.train-epochs", 200), 1, 5000);
        this.modelPunishVL = clampInt(config.getInt("physics-inference.mlp-network.model-punish-vl", 20), 1, 100);
        this.anomalyThreshold = clampDouble(config.getDouble("physics-inference.mlp-network.anomaly-threshold", 0.70), 0.1, 0.99);

        // 双模型 AB 架构配置
        this.dualModelEnabled = config.getBoolean("physics-inference.dual-model.enabled", true);
        this.dualModelAWeight = clampDouble(config.getDouble("physics-inference.dual-model.model-a-weight", 0.4), 0.0, 1.0);
        this.dualModelBWeight = clampDouble(config.getDouble("physics-inference.dual-model.model-b-weight", 0.4), 0.0, 1.0);
        this.dualModelRuleWeight = clampDouble(config.getDouble("physics-inference.dual-model.rule-weight", 0.2), 0.0, 1.0);
        this.dualConfirmThreshold = clampDouble(config.getDouble("physics-inference.dual-model.dual-confirm-threshold", 0.6), 0.1, 0.95);
        this.singleConvictThreshold = clampDouble(config.getDouble("physics-inference.dual-model.single-convict-threshold", 0.75), 0.1, 0.99);
        this.highRiskBWeightBoost = clampDouble(config.getDouble("physics-inference.dual-model.highrisk-b-weight-boost", 1.5), 1.0, 3.0);
        this.threatSamplingTarget = clampInt(config.getInt("physics-inference.dual-model.threat-sampling-target", 2000), 100, 50000);
        this.realtimeOnlineLearning = config.getBoolean("physics-inference.dual-model.realtime-online-learning", true);

        // 威胁模型网络参数 (B模型)
        this.threatMovementHidden1 = clampInt(config.getInt("physics-inference.mlp-network.threat-movement.hidden1", 56), 4, 256);
        this.threatMovementHidden2 = clampInt(config.getInt("physics-inference.mlp-network.threat-movement.hidden2", 32), 4, 256);
        this.threatMovementLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.threat-movement.learning-rate", 0.01), 0.0001, 0.5);
        this.threatCombatHidden1 = clampInt(config.getInt("physics-inference.mlp-network.threat-combat.hidden1", 16), 4, 256);
        this.threatCombatHidden2 = clampInt(config.getInt("physics-inference.mlp-network.threat-combat.hidden2", 8), 4, 256);
        this.threatCombatLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.threat-combat.learning-rate", 0.01), 0.0001, 0.5);
        this.threatFusionHidden1 = clampInt(config.getInt("physics-inference.mlp-network.threat-fusion.hidden1", 12), 4, 64);
        this.threatFusionLearningRate = clampDouble(config.getDouble("physics-inference.mlp-network.threat-fusion.learning-rate", 0.01), 0.0001, 0.5);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
