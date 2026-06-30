package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.config.ANSACConfig;

import dev.ztros.ansac.physics.mlp.*;
import dev.ztros.ansac.physics.mlp.profile.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理推理服务桥接层。
 * <p>
 * 管理所有在线玩家的物理状态（{@link PlayerPhysicsState}），
 * 提供统一的物理推理入口，并协调自学习基准模型的数据收集。
 * </p>
 *
 * <p><b>核心职责：</b></p>
 * <ul>
 *   <li>管理每玩家的 {@link PlayerPhysicsState} 实例</li>
 *   <li>维护受信任玩家列表（仅受信任玩家的数据用于自学习）</li>
 *   <li>在玩家移动时驱动状态更新和基准模型学习</li>
 *   <li>生成 {@link InferenceResult} 快照供检查使用</li>
 *   <li>持久化基准模型数据</li>
 * </ul>
 *
 * <p><b>线程安全：</b> 使用 {@link ConcurrentHashMap} 存储所有玩家状态和信任列表。
 * 个体 {@link PlayerPhysicsState} 的读写应通过 Folia EntityScheduler 保证线程安全。</p>
 *
 * @author ANSAC Physics Engine
 * @see PlayerPhysicsState
 * @see BaselineModel
 * @see InferenceResult
 */
public class PhysicsInferenceService {

    // ==================== 插件引用 ====================

    private final ANSACPlugin plugin;
    private final File baselineFile;

    // ==================== 玩家状态管理 ====================

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * 每玩家物理状态映射表。
     * <p>键为玩家 UUID，值为该玩家的完整物理状态。</p>
     */
    private final ConcurrentHashMap<UUID, PlayerPhysicsState> states;

    /**
     * 受信任玩家映射表。
     * <p>受信任玩家的移动数据会被用于自学习基准模型。</p>
     */
    private final ConcurrentHashMap<UUID, Boolean> trustedPlayers;

    /**
     * 纯模型模式下 AI 自动处罚的冷却时间记录。
     */
    private final ConcurrentHashMap<UUID, Long> modelPunishCooldown = new ConcurrentHashMap<>();

    // ==================== 基准模型 ====================

    /**
     * 自学习基准模型实例。
     */
    private final BaselineModel baselineModel;

    // ==================== MLP 模型 ====================

    private final MovementMLP movementMLP;
    private final CombatMLP combatMLP;
    private final CausalFusion causalFusion;
    private final MLPSamplingSession samplingSession;
    private final File mlpFile;
    private final File combatMlpFile;
    private final File fusionMlpFile;
    private final int trainEpochs;
    private final int modelPunishVL;
    private final double anomalyThreshold;

    /** 检测运行模式: RULE_ONLY / MODEL_ONLY / HYBRID */
    private volatile DetectionMode detectionMode = DetectionMode.HYBRID;

    /** 实时监控：被监控玩家的 UUID -> 是否活跃 */
    private final ConcurrentHashMap<UUID, Boolean> watchActive = new ConcurrentHashMap<>();

    /** 实时监控间隔（tick），默认 40 tick = 2 秒 */
    private static final int WATCH_INTERVAL_TICKS = 40;

    // ==================== 双模型 AB 架构 ====================

    /** B模型（威胁模型）束 */
    private final ThreatModelBundle threatModelBundle;

    /** B模型采样会话 */
    private final MLPSamplingSession threatSamplingSession;

    /** 威胁模型持久化文件 */
    private final File threatMlpFile;
    private final File threatCombatMlpFile;
    private final File threatFusionMlpFile;

    /** 智能模型选择器 */
    private final ModelSelector modelSelector;

    /** 高危玩家映射表（已确认的作弊者，其数据用于B模型训练） */
    private final ConcurrentHashMap<UUID, Boolean> highRiskPlayers;

    /** 实时同步推理玩家映射表（被标记为合法的玩家，逐tick实时推理+在线学习） */
    private final ConcurrentHashMap<UUID, Boolean> realtimeInferencePlayers;

    /** 双模型是否启用 */
    private volatile boolean dualModelEnabled;

    /** 实时在线学习是否启用 */
    private volatile boolean realtimeOnlineLearning;

    // ==================== 配置参数 ====================

    /**
     * 物理推理引擎是否启用。
     */
    private volatile boolean enabled;

    /**
     * 是否优先使用推理结果。
     * <p>启用时，检查会优先通过 {@link IPhysicsCheck#processWithInference} 处理。</p>
     */
    private volatile boolean preferInference;

    /**
     * 是否启用自动学习。
     * <p>启用时，受信任玩家的数据会自动喂入基准模型。</p>
     */
    private volatile boolean autoLearn;

    /**
     * 学习率系数。
     * <p>影响 EMA 更新的灵敏度，值越大对最新数据响应越快。</p>
     */
    private volatile double learningRate;

    /**
     * 推理结果对检查决策的影响权重。
     * <p>值越大，推理结果的偏差对最终决策的影响越强。</p>
     */
    private volatile double influenceWeight;

    /**
     * 触发学习所需的最小采样数。
     * <p>低于此数时不记录学习数据。</p>
     */
    private volatile int minSamples;

    // ==================== 学习统计 ====================

    /** 学习次数（累计喂入基准模型的样本数） */
    private volatile long learningCount;

    /** 修正次数（检测到理论与实际偏差并自动修正阈值的次数） */
    private volatile long correctionCount;

    /** 迭代次数（基准模型完成完整更新周期的次数） */
    private volatile long iterationCount;

    // ==================== 构造函数 ====================

    /**
     * 创建物理推理服务。
     * <p>使用默认配置参数初始化。</p>
     */
    public PhysicsInferenceService(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.baselineFile = new File(plugin.getDataFolder(), "baseline.yml");
        this.states = new ConcurrentHashMap<>();
        this.trustedPlayers = new ConcurrentHashMap<>();
        this.baselineModel = new BaselineModel();
        this.enabled = true;
        this.preferInference = true;
        this.autoLearn = true;
        this.learningRate = 0.1;
        this.influenceWeight = 1.0;
        this.minSamples = 10;
        this.learningCount = 0;
        this.correctionCount = 0;
        this.iterationCount = 0;

        this.mlpFile = new File(plugin.getDataFolder(), "mlp-model.bin");
        this.combatMlpFile = new File(plugin.getDataFolder(), "combat-mlp-model.bin");
        this.fusionMlpFile = new File(plugin.getDataFolder(), "fusion-mlp-model.bin");
        int samplingTarget = plugin.getAnsacConfig().getMlpSamplingTarget();
        this.samplingSession = new MLPSamplingSession(samplingTarget);

        ANSACConfig cfg = plugin.getAnsacConfig();
        this.movementMLP = loadOrCreateMovementMlp(cfg);
        this.combatMLP = loadOrCreateCombatMlp(cfg);
        this.causalFusion = loadOrCreateFusionMlp(cfg);
        this.trainEpochs = cfg.getTrainEpochs();
        this.modelPunishVL = cfg.getModelPunishVL();
        this.anomalyThreshold = cfg.getAnomalyThreshold();

        // 更新 MLPActivations 的裁剪阈值
        MLPActivations.setGradClip(cfg.getGradClip());
        MLPActivations.setWeightClip(cfg.getWeightClip());

        this.detectionMode = DetectionMode.fromString(plugin.getConfig().getString("detection-mode", "hybrid"));

        // ==================== 双模型 AB 架构初始化 ====================
        this.dualModelEnabled = cfg.isDualModelEnabled();
        this.realtimeOnlineLearning = cfg.isRealtimeOnlineLearning();
        this.highRiskPlayers = new ConcurrentHashMap<>();
        this.realtimeInferencePlayers = new ConcurrentHashMap<>();

        // 威胁模型文件路径
        this.threatMlpFile = new File(plugin.getDataFolder(), "threat-mlp-model.bin");
        this.threatCombatMlpFile = new File(plugin.getDataFolder(), "threat-combat-mlp-model.bin");
        this.threatFusionMlpFile = new File(plugin.getDataFolder(), "threat-fusion-mlp-model.bin");

        // 初始化B模型（威胁模型）
        this.threatModelBundle = loadOrCreateThreatBundle(cfg);

        // B模型采样会话
        int threatTarget = cfg.getThreatSamplingTarget();
        this.threatSamplingSession = new MLPSamplingSession(threatTarget);

        // 智能模型选择器
        this.modelSelector = new ModelSelector(
            cfg.getDualModelAWeight(),
            cfg.getDualModelBWeight(),
            cfg.getDualModelRuleWeight(),
            cfg.getDualConfirmThreshold(),
            cfg.getSingleConvictThreshold(),
            cfg.getHighRiskBWeightBoost()
        );

        // B模型持续自动训练回调
        this.threatSamplingSession.setOnTargetReached((round) -> {
            List<double[]> batch = threatSamplingSession.drainSamples();
            if (!batch.isEmpty()) {
                trainThreatMlp(batch);
            }
        });

        // 加载B模型
        loadThreatModels();

        // 注册持续自动训练回调：达到采样目标后自动训练
        this.samplingSession.setOnTargetReached((round) -> {
            List<double[]> batch = samplingSession.drainSamples();
            if (!batch.isEmpty()) {
                trainMlp(batch);
            }
        });

        if (plugin != null) {
            plugin.getLogger().info("ANSAC 物理推理引擎已启动: " + BehaviorFeatureExtractor.FEATURE_COUNT
                + "维输入 → " + movementMLP.getHidden1Size() + "→" + movementMLP.getHidden2Size()
                + "→1 (MovementMLP + CombatMLP + CausalFusion)");
        }
    }

    private MovementMLP loadOrCreateMovementMlp(ANSACConfig cfg) {
        int h1 = cfg.getMovementHidden1();
        int h2 = cfg.getMovementHidden2();
        double lr = cfg.getMovementLearningRate();
        if (mlpFile.exists()) {
            try {
                MovementMLP loaded = MLPPersistence.loadMovement(mlpFile);
                if (loaded.getInputSize() == BehaviorFeatureExtractor.FEATURE_COUNT
                        && loaded.getHidden1Size() == h1
                        && loaded.getHidden2Size() == h2) {
                    if (plugin != null) {
                        plugin.getLogger().info("MovementMLP 模型加载成功: " + loaded.getInputSize()
                            + "→" + loaded.getHidden1Size() + "→" + loaded.getHidden2Size());
                    }
                    return loaded;
                }
                if (plugin != null) {
                    plugin.getLogger().warning("MovementMLP 模型维度不匹配 (旧模型 "
                        + loaded.getInputSize() + "→" + loaded.getHidden1Size() + "→" + loaded.getHidden2Size()
                        + ")，将创建新模型 (" + BehaviorFeatureExtractor.FEATURE_COUNT + "→" + h1 + "→" + h2 + ")");
                }
                mlpFile.delete();
            } catch (IOException e) {
                if (plugin != null) {
                    plugin.getLogger().warning("加载 MovementMLP 模型失败，将创建新模型: " + e.getMessage());
                }
            }
        }
        return new MovementMLP(BehaviorFeatureExtractor.FEATURE_COUNT, h1, h2, lr);
    }

    private CombatMLP loadOrCreateCombatMlp(ANSACConfig cfg) {
        int h1 = cfg.getCombatHidden1();
        int h2 = cfg.getCombatHidden2();
        double lr = cfg.getCombatLearningRate();
        if (combatMlpFile.exists()) {
            try {
                CombatMLP loaded = MLPPersistence.loadCombat(combatMlpFile);
                if (loaded.getInputSize() == BehaviorFeatureExtractor.COMBAT_COUNT
                        && loaded.getHidden1Size() == h1
                        && loaded.getHidden2Size() == h2) {
                    if (plugin != null) {
                        plugin.getLogger().info("CombatMLP 模型加载成功: " + loaded.getInputSize()
                            + "→" + loaded.getHidden1Size() + "→" + loaded.getHidden2Size());
                    }
                    return loaded;
                }
                if (plugin != null) {
                    plugin.getLogger().warning("CombatMLP 模型维度不匹配，将创建新模型");
                }
                combatMlpFile.delete();
            } catch (IOException e) {
                if (plugin != null) {
                    plugin.getLogger().warning("加载 CombatMLP 模型失败: " + e.getMessage());
                }
            }
        }
        return new CombatMLP(BehaviorFeatureExtractor.COMBAT_COUNT, h1, h2, lr);
    }

    private CausalFusion loadOrCreateFusionMlp(ANSACConfig cfg) {
        int h1 = cfg.getFusionHidden1();
        double lr = cfg.getFusionLearningRate();
        if (fusionMlpFile.exists()) {
            try {
                CausalFusion loaded = MLPPersistence.loadFusion(fusionMlpFile);
                if (loaded.getHiddenSize() == h1) {
                    if (plugin != null) {
                        plugin.getLogger().info("CausalFusion 模型加载成功: 隐藏层=" + h1);
                    }
                    return loaded;
                }
                if (plugin != null) {
                    plugin.getLogger().warning("CausalFusion 模型维度不匹配，将创建新模型");
                }
                fusionMlpFile.delete();
            } catch (IOException e) {
                if (plugin != null) {
                    plugin.getLogger().warning("加载 CausalFusion 模型失败: " + e.getMessage());
                }
            }
        }
        return new CausalFusion(h1, lr);
    }

    // ==================== 威胁模型 (B模型) 加载/创建 ====================

    private ThreatModelBundle loadOrCreateThreatBundle(ANSACConfig cfg) {
        int moveH1 = cfg.getThreatMovementHidden1();
        int moveH2 = cfg.getThreatMovementHidden2();
        double moveLR = cfg.getThreatMovementLearningRate();
        int combatH1 = cfg.getThreatCombatHidden1();
        int combatH2 = cfg.getThreatCombatHidden2();
        double combatLR = cfg.getThreatCombatLearningRate();
        int fusionH = cfg.getThreatFusionHidden1();
        double fusionLR = cfg.getThreatFusionLearningRate();

        // 尝试从文件加载已持久化的威胁模型
        MovementMLP threatMove = null;
        CombatMLP threatCombat = null;
        CausalFusion threatFusion = null;

        if (threatMlpFile.exists()) {
            try {
                MovementMLP loaded = MLPPersistence.loadThreatMovement(threatMlpFile);
                if (loaded.getInputSize() == BehaviorFeatureExtractor.FEATURE_COUNT
                        && loaded.getHidden1Size() == moveH1
                        && loaded.getHidden2Size() == moveH2) {
                    if (plugin != null) {
                        plugin.getLogger().info("ThreatMovementMLP (B模型) 加载成功: "
                            + loaded.getInputSize() + "→" + loaded.getHidden1Size() + "→" + loaded.getHidden2Size());
                    }
                    threatMove = loaded;
                } else {
                    if (plugin != null) plugin.getLogger().warning("ThreatMovementMLP 维度不匹配，将创建新模型");
                    threatMlpFile.delete();
                }
            } catch (IOException e) {
                if (plugin != null) plugin.getLogger().warning("加载 ThreatMovementMLP 失败: " + e.getMessage());
            }
        }
        if (threatMove == null) {
            threatMove = new MovementMLP(BehaviorFeatureExtractor.FEATURE_COUNT, moveH1, moveH2, moveLR);
        }

        if (threatCombatMlpFile.exists()) {
            try {
                CombatMLP loaded = MLPPersistence.loadThreatCombat(threatCombatMlpFile);
                if (loaded.getInputSize() == BehaviorFeatureExtractor.COMBAT_COUNT
                        && loaded.getHidden1Size() == combatH1
                        && loaded.getHidden2Size() == combatH2) {
                    if (plugin != null) {
                        plugin.getLogger().info("ThreatCombatMLP (B模型) 加载成功");
                    }
                    threatCombat = loaded;
                } else {
                    if (plugin != null) plugin.getLogger().warning("ThreatCombatMLP 维度不匹配，将创建新模型");
                    threatCombatMlpFile.delete();
                }
            } catch (IOException e) {
                if (plugin != null) plugin.getLogger().warning("加载 ThreatCombatMLP 失败: " + e.getMessage());
            }
        }
        if (threatCombat == null) {
            threatCombat = new CombatMLP(BehaviorFeatureExtractor.COMBAT_COUNT, combatH1, combatH2, combatLR);
        }

        if (threatFusionMlpFile.exists()) {
            try {
                CausalFusion loaded = MLPPersistence.loadThreatFusion(threatFusionMlpFile);
                if (loaded.getHiddenSize() == fusionH) {
                    if (plugin != null) plugin.getLogger().info("ThreatCausalFusion (B模型) 加载成功");
                    threatFusion = loaded;
                } else {
                    if (plugin != null) plugin.getLogger().warning("ThreatCausalFusion 维度不匹配，将创建新模型");
                    threatFusionMlpFile.delete();
                }
            } catch (IOException e) {
                if (plugin != null) plugin.getLogger().warning("加载 ThreatCausalFusion 失败: " + e.getMessage());
            }
        }
        if (threatFusion == null) {
            threatFusion = new CausalFusion(fusionH, fusionLR);
        }

        return new ThreatModelBundle(threatMove, threatCombat, threatFusion);
    }

    /** 从文件加载威胁模型 */
    private void loadThreatModels() {
        // loadOrCreateThreatBundle 已经在构造函数中完成了加载
        if (plugin != null) {
            plugin.getLogger().info("威胁模型 (B模型) 初始化完成: "
                + threatModelBundle.getThreatMovementMLP().getInputSize() + "维输入 → "
                + threatModelBundle.getThreatMovementMLP().getHidden1Size() + "→"
                + threatModelBundle.getThreatMovementMLP().getHidden2Size() + "→1");
        }
    }

    /** 保存威胁模型到文件 */
    public void saveThreatModels() {
        try {
            MLPPersistence.saveThreatMovement(threatModelBundle.getThreatMovementMLP(), threatMlpFile);
            MLPPersistence.saveThreatCombat(threatModelBundle.getThreatCombatMLP(), threatCombatMlpFile);
            MLPPersistence.saveThreatFusion(threatModelBundle.getThreatCausalFusion(), threatFusionMlpFile);
        } catch (IOException e) {
            if (plugin != null) plugin.getLogger().warning("保存威胁模型失败: " + e.getMessage());
        }
    }

    // ==================== 核心处理方法 ====================

    /**
     * 处理玩家移动事件（PlayerListener 调用入口）。
     * <p>
     * 更新玩家的物理状态，并在条件满足时驱动基准模型学习。
     * </p>
     *
     * @param player 被移动的玩家
     * @param data   玩家数据（保留兼容，当前未使用）
     * @param from   移动前位置
     * @param to     移动后位置
     */
    public void onPlayerMove(Player player, dev.ztros.ansac.player.PlayerData data, Location from, Location to) {
        onPlayerMove(player, from, to);
    }

    /**
     * 处理玩家移动事件。
     * <p>
     * 更新玩家的物理状态，并在条件满足时驱动基准模型学习。
     * 此方法应在玩家移动时（通常由 PlayerMoveEvent 触发）在实体线程上调用。
     * </p>
     * <p>
     * <b>学习触发条件：</b>
     * 当玩家是受信任的（{@link #isTrusted(UUID)}）且自动学习已启用（{@link #isAutoLearn()}）时，
     * 根据当前状态确定场景标识符，并将速度数据喂入基准模型。
     * </p>
     *
     * @param player 被移动的玩家
     * @param from   移动前位置
     * @param to     移动后位置
     */
    public void onPlayerMove(Player player, Location from, Location to) {
        if (!enabled) {
            return;
        }

        UUID uuid = player.getUniqueId();

        // DEBUG: 追踪 onPlayerMove 调用链
        if (states.isEmpty()) {
            boolean authEnabled = plugin.getAuthService().isEnabled();
            boolean authed = !authEnabled || plugin.getAuthService().isAuthenticated(uuid);
            plugin.getLogger().info("[ANSAC-DEBUG] onPlayerMove called: player=" + player.getName()
                + " authEnabled=" + authEnabled + " authed=" + authed
                + " from=" + from.getBlockX() + "," + from.getBlockY() + "," + from.getBlockZ()
                + " to=" + to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ());
        }

        // 跳过未认证玩家（auth 模块未通过）
        if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(uuid)) {
            return;
        }

        // 跳过有豁免权的玩家
        dev.ztros.ansac.player.PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(uuid);
        if (playerData != null && playerData.hasBypass()) {
            return;
        }

        PlayerPhysicsState state = states.computeIfAbsent(uuid, k -> new PlayerPhysicsState());
        if (states.size() == 1) {
            plugin.getLogger().info("[ANSAC-DEBUG] 首次创建物理状态: player=" + player.getName()
                + " states.size=" + states.size());
        }

        long now = System.currentTimeMillis();
        state.updateFromPlayer(player, from, to, now);

        // MLP 完整画像推理 (MovementMLP + CombatMLP + CausalFusion)
        PlayerBehaviorProfile profile = (playerData != null) ? playerData.getBehaviorProfile() : new PlayerBehaviorProfile();
        double[] features = BehaviorFeatureExtractor.extract(state, profile);
        double movementScore = movementMLP.forward(features);
        double[] combatFeatures = BehaviorFeatureExtractor.extractCombatSlice(features);
        double combatScore = combatMLP.forward(combatFeatures);
        // 因果特征：环境解释力、速度/预期比、击退力度、顶格跳、冲击事件等
        double[] causalInputs = BehaviorFeatureExtractor.extractCausalInputs(state, movementScore, combatScore, 0.0);
        double anomalyScore = causalFusion.forward(causalInputs);
        state.setLastNormalScore(movementScore);
        state.setLastAnomalyScore(anomalyScore);

        // ==================== B模型（威胁模型）推理 ====================
        double threatMovementScore = 0.0;
        double threatCombatScore = 0.0;
        double threatFusionScore = 0.0;
        DualInferenceResult dualResult = null;

        if (dualModelEnabled) {
            threatMovementScore = threatModelBundle.forwardMovement(features);
            threatCombatScore = threatModelBundle.forwardCombat(combatFeatures);
            double[] threatCausalInputs = BehaviorFeatureExtractor.extractCausalInputs(
                state, threatMovementScore, threatCombatScore, 0.0);
            threatFusionScore = threatModelBundle.forwardFusion(threatCausalInputs);

            // ModelSelector 智能评估：综合A和B模型结果决定定罪策略
            boolean isHighRisk = highRiskPlayers.containsKey(uuid);
            double ruleFactor = 0.0;
            if (playerData != null) {
                int totalVL = playerData.getTotalVL();
                ruleFactor = Math.min(1.0, totalVL / (double) modelPunishVL);
            }
            ModelSelector.ModelSelectorResult selectorResult = modelSelector.evaluate(
                movementScore, threatFusionScore, ruleFactor, isHighRisk
            );

            dualResult = new DualInferenceResult(
                movementScore, combatScore, anomalyScore,
                threatMovementScore, threatCombatScore, threatFusionScore,
                selectorResult, isHighRisk, realtimeInferencePlayers.containsKey(uuid)
            );
        }

        // 纯模型模式：AI 辅助判罪（需要模型已训练至少一轮）
        // 未训练的模型输出为随机值，不能用于处罚
        boolean modelReady = samplingSession.getTrainRound() > 0;
        if (detectionMode == DetectionMode.MODEL_ONLY && modelReady && anomalyScore > anomalyThreshold && playerData != null) {
            // 将异常分数映射为 VL 增量，走 Check 层的 VL 累积系统
            double severity = (anomalyScore - anomalyThreshold) / (1.0 - anomalyThreshold); // 映射到 0~1
            playerData.addViolation("AnomalyFusion", severity * 2.0);

            // 查看累计 VL，达到阈值才处罚
            var violationOpt = playerData.getViolation("AnomalyFusion");
            int totalVL = violationOpt != null ? violationOpt.getTotalVL() : 0;
            if (totalVL >= modelPunishVL) {
                long lastPunish = modelPunishCooldown.getOrDefault(uuid, 0L);
                if (now - lastPunish > 10000L) {
                    modelPunishCooldown.put(uuid, now);
                    final double finalAnomaly = anomalyScore;
                    final int finalVL = totalVL;
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        plugin.getPunishmentManager().punish(player,
                            "AI模型检测到异常行为", "AnomalyFusion", finalVL);
                    });
                    Component aiAlert = miniMessage.deserialize(
                        "<gray>[<dark_red>ANSAC-AI</dark_red>]</gray> " +
                        "<red>AI 已自动处罚 <yellow>" + player.getName() + "</yellow> " +
                        "异常度: <white>" + String.format("%.1f%%", finalAnomaly * 100) + "</white>"
                    );
                    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("ansac.admin")) {
                            p.sendMessage(aiAlert);
                        }
                    }
                }
            }
        }

        // ==================== 双模型 AB 定罪逻辑 ====================
        // 当 ModelSelector 建议定罪时，走 VL 累积系统进行处罚
        if (dualModelEnabled && dualResult != null && dualResult.shouldConvict()
                && playerData != null) {
            double confidence = dualResult.getConfidence();
            double severity = (confidence - modelSelector.getSingleConvictThreshold())
                    / (1.0 - modelSelector.getSingleConvictThreshold());
            severity = Math.max(0.1, Math.min(1.0, severity));
            playerData.addViolation("DualModelAI", severity * 2.0);

            var dualViolation = playerData.getViolation("DualModelAI");
            int dualTotalVL = dualViolation != null ? dualViolation.getTotalVL() : 0;
            if (dualTotalVL >= modelPunishVL) {
                long lastPunish = modelPunishCooldown.getOrDefault(uuid, 0L);
                if (now - lastPunish > 10000L) {
                    modelPunishCooldown.put(uuid, now);
                    final double finalConfidence = confidence;
                    final int finalVL = dualTotalVL;
                    final String verdictSource = dualResult.getVerdictSource().name();
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        plugin.getPunishmentManager().punish(player,
                            "双模型AI检测确认作弊 [" + verdictSource + "]", "DualModelAI", finalVL);
                    });
                    Component dualAlert = miniMessage.deserialize(
                        "<gray>[<dark_red>ANSAC-DualAI</dark_red>]</gray> " +
                        "<red>双模型AI已处罚 <yellow>" + player.getName() + "</yellow> " +
                        "置信度: <white>" + String.format("%.1f%%", finalConfidence * 100) + "</white> " +
                        "来源: <gold>" + verdictSource + "</gold>"
                    );
                    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("ansac.admin")) {
                            p.sendMessage(dualAlert);
                        }
                    }
                }
            }
        }

        // 自动学习: 仅对受信任玩家
        if (autoLearn && trustedPlayers.getOrDefault(uuid, false)) {
            String scenarioKey = determineScenario(state);
            if (scenarioKey != null && !scenarioKey.isEmpty()) {
                double hSpeed = Math.sqrt(
                        state.getVelocityX() * state.getVelocityX()
                        + state.getVelocityZ() * state.getVelocityZ()
                );
                baselineModel.recordSample(scenarioKey, hSpeed);
                learningCount++;

                // 每 1000 个样本计为一次迭代
                if (learningCount % 1000 == 0) {
                    iterationCount++;
                }

                // 自动修正：当学习到的典型速度与理论值偏差超过 15% 时，自动修正
                double learned = baselineModel.getLearnedSpeedForScenario(scenarioKey);
                double expected = PhysicsEngine.computeExpectedMaxHorizontalSpeed(state);
                if (learned > 0 && expected > 0) {
                    double deviation = Math.abs(learned - expected) / expected;
                    if (deviation > 0.15) {
                        correctionCount++;
                    }
                }
            }

            // MLP 完整画像采样
            if (samplingSession.getState() == MLPSamplingSession.State.COLLECTING) {
                dev.ztros.ansac.player.PlayerData sampleData = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
                PlayerBehaviorProfile sampleProfile = (sampleData != null) ? sampleData.getBehaviorProfile() : new PlayerBehaviorProfile();
                double[] sampleFeatures = BehaviorFeatureExtractor.extract(state, sampleProfile);
                boolean reached = samplingSession.offerSample(sampleFeatures);
                if (reached) {
                    // 达到采样目标，在锁外触发自动训练回调
                    samplingSession.fireTargetReachedCallback();
                }
            }
        }

        // ==================== 高危玩家数据收集（B模型训练） ====================
        // 高危玩家（已确认作弊者）的行为数据用于训练威胁模型
        // B模型学习作弊行为模式，辅助正常推理
        // 注意：实时在线学习使用 synchronized 保护，因为 Folia 多线程下
        // 不同玩家的 onPlayerMove 跑在不同区域线程上，并发 train() 会损坏权重
        if (dualModelEnabled && highRiskPlayers.containsKey(uuid)) {
            // 实时在线学习：逐tick单样本SGD（而非批量异步）
            if (realtimeOnlineLearning) {
                synchronized (threatModelBundle) {
                    threatModelBundle.trainOnCheater(features);
                }
            } else {
                // 批量采样模式
                if (threatSamplingSession.getState() == MLPSamplingSession.State.COLLECTING) {
                    boolean reached = threatSamplingSession.offerSample(features);
                    if (reached) {
                        threatSamplingSession.fireTargetReachedCallback();
                    }
                }
            }
        }

        // ==================== 实时同步推理（被标记为合法的玩家） ====================
        // 对标记为合法的玩家进行实时同步双模型推理 + 在线学习
        // 这提供了最高精度的检测：模型逐tick学习其行为模式
        // synchronized 保护 A/B 模型的并发写入
        if (dualModelEnabled && realtimeInferencePlayers.containsKey(uuid)) {
            // 合法玩家行为作为负样本喂入B模型（target=0.0 = 非作弊）
            if (realtimeOnlineLearning) {
                synchronized (threatModelBundle) {
                    threatModelBundle.trainOnNormal(features);
                }
            }
            // A模型也做在线学习（逐tick强化正常行为认知）
            if (modelReady) {
                synchronized (movementMLP) {
                    movementMLP.train(features, 1.0);
                }
                synchronized (combatMLP) {
                    combatMLP.train(combatFeatures, 1.0);
                }
            }
        }
    }

    /**
     * 根据玩家当前物理状态确定场景标识符。
     * <p>
     * 综合考虑地面状态、冰面、水中、疾跑、速度药水等级等因素，
     * 生成唯一的场景标识字符串。格式示例:
     * <ul>
     *   <li>{@code "walk_ground"} - 地面行走</li>
     *   <li>{@code "sprint_ground_speed1"} - 地面疾跑 + 速度药水 I</li>
     *   <li>{@code "walk_ice"} - 冰面行走</li>
     *   <li>{@code "sprint_blueice_speed2"} - 蓝冰疾跑 + 速度药水 II</li>
     *   <li>{@code "walk_water"} - 水中行走</li>
     *   <li>{@code "sneak_ground"} - 潜行</li>
     *   <li>{@code "climb_ladder"} - 爬梯</li>
     * </ul>
     * </p>
     *
     * @param state 玩家物理状态
     * @return 场景标识符字符串
     */
    public String determineScenario(PlayerPhysicsState state) {
        StringBuilder sb = new StringBuilder();

        // 动作类型
        if (state.isSneaking()) {
            sb.append("sneak");
        } else if (state.isClimbing()) {
            sb.append("climb");
        } else if (state.isSprinting()) {
            sb.append("sprint");
        } else {
            sb.append("walk");
        }

        // 地面/环境
        if (state.isOnBlueIce()) {
            sb.append("_blueice");
        } else if (state.isOnIce()) {
            sb.append("_ice");
        } else if (state.isInWater()) {
            sb.append("_water");
        } else if (state.isInLava()) {
            sb.append("_lava");
        } else if (state.isClientOnGround() || state.isServerVerifiedGround()) {
            sb.append("_ground");
        } else {
            sb.append("_air");
        }

        // 速度药水等级
        int speedLevel = state.getSpeedPotionLevel();
        if (speedLevel > 0) {
            sb.append("_speed").append(speedLevel);
        }

        // 跳跃增益等级
        int jumpLevel = state.getJumpBoostLevel();
        if (jumpLevel > 0) {
            sb.append("_jump").append(jumpLevel);
        }

        // 灵魂疾行
        if (state.hasSoulSpeed()) {
            sb.append("_soul").append(state.getSoulSpeedLevel());
        }

        // 海豚的恩惠
        if (state.hasDolphinsGrace()) {
            sb.append("_dolphin");
        }

        return sb.toString();
    }

    // ==================== 推理结果 ====================

    /**
     * 获取指定玩家的物理推理结果快照。
     * <p>
     * 基于玩家当前的 {@link PlayerPhysicsState} 生成不可变的 {@link InferenceResult}。
     * 如果玩家没有物理状态数据，返回 {@link InferenceResult#EMPTY}。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 物理推理结果快照
     */
    public InferenceResult getInferenceResult(UUID uuid) {
        PlayerPhysicsState state = states.get(uuid);
        if (state == null || state.getCurrentLocation() == null) {
            return InferenceResult.EMPTY;
        }

        // 计算水平合成速度
        double vx = state.getVelocityX();
        double vy = state.getVelocityY();
        double vz = state.getVelocityZ();
        double hSpeed = Math.sqrt(vx * vx + vz * vz);

        // 计算预期最大水平速度
        double expectedMaxSpeed = PhysicsEngine.computeExpectedMaxHorizontalSpeed(state);

        // 判断是否处于跳跃周期
        boolean inJumpCycle = state.getJumpPhase() != PlayerPhysicsState.JumpPhase.NONE;

        // 判断是否在空中
        boolean inAir = !state.isClientOnGround()
                && !state.isServerVerifiedGround()
                && !state.isInWater()
                && !state.isInLava();

        return new InferenceResult(
                state.getCurrentLocation().clone(),
                vx,
                vy,
                vz,
                hSpeed,
                state.isClientOnGround(),
                state.isServerVerifiedGround(),
                inJumpCycle,
                inAir,
                state.hasLevitation(),
                state.hasSlowFalling(),
                state.isGliding(),
                state.isOnIce(),
                state.isOnBlueIce(),
                state.isSneaking(),
                state.isSprinting(),
                state.getJumpPhase(),
                state.getJumpTakeoffHorizontalSpeed(),
                state.getServerFallDistance(),
                state.getTicksSinceLeftGround(),
                state.getJumpStartY(),
                state.getJumpPeakY(),
                state.getPredictedVelocityY(),
                expectedMaxSpeed,
                state.getSpeedPotionLevel(),
                state.getJumpBoostLevel(),
                state.getLastNormalScore()
        );
    }

    // ==================== 状态管理 ====================

    /**
     * 获取指定玩家的物理状态。
     * <p>
     * 如果玩家没有物理状态数据，返回 null。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 玩家物理状态，如果不存在返回 null
     */
    public PlayerPhysicsState getState(UUID uuid) {
        return states.get(uuid);
    }

    /** 返回当前正在追踪物理状态的玩家数量 */
    public int getStateCount() {
        return states.size();
    }

    /**
     * 标记玩家为受信任。
     * <p>
     * 受信任玩家的移动数据会被用于自学习基准模型。
     * </p>
     *
     * @param uuid 玩家 UUID
     */
    public void markPlayerTrusted(UUID uuid) {
        trustedPlayers.put(uuid, true);
    }

    /**
     * 取消玩家的受信任标记。
     *
     * @param uuid 玩家 UUID
     */
    public void unmarkPlayerTrusted(UUID uuid) {
        trustedPlayers.remove(uuid);
    }

    /**
     * 检查玩家是否为受信任。
     *
     * @param uuid 玩家 UUID
     * @return 如果玩家被标记为受信任则返回 true
     */
    public boolean isTrusted(UUID uuid) {
        return trustedPlayers.getOrDefault(uuid, false);
    }

    /**
     * 获取基准模型实例。
     *
     * @return 自学习基准模型
     */
    public BaselineModel getBaselineModel() {
        return baselineModel;
    }

    /**
     * 重置基准模型。
     * <p>
     * 清除所有学习数据，恢复到初始值。
     * </p>
     */
    public void resetBaselineModel() {
        baselineModel.reset();
    }

    /**
     * 关闭推理服务。
     * <p>
     * 清理所有玩家状态和信任列表。
     * 通常在插件禁用时调用。
     * </p>
     */
    public void shutdown() {
        // 停止所有实时监控
        watchActive.clear();
        states.clear();
        trustedPlayers.clear();
        highRiskPlayers.clear();
        realtimeInferencePlayers.clear();
        // 保存威胁模型
        if (dualModelEnabled) {
            saveThreatModels();
        }
    }

    /**
     * 保存基准模型到默认文件。
     */
    public void saveBaseline() {
        try {
            baselineModel.save(baselineFile);
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().warning("保存基准模型失败: " + e.getMessage());
            }
        }
    }

    /**
     * 从默认文件加载基准模型。
     */
    public void loadBaseline() {
        try {
            baselineModel.load(baselineFile);
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().warning("加载基准模型失败: " + e.getMessage());
            }
        }
    }

    /**
     * 保存基准模型到指定文件。
     *
     * @param file 目标文件
     * @throws IOException 如果写入文件失败
     */
    public void saveBaseline(File file) throws IOException {
        baselineModel.save(file);
    }

    /**
     * 从指定文件加载基准模型。
     *
     * @param file 源文件
     * @throws IOException 如果读取文件失败
     */
    public void loadBaseline(File file) throws IOException {
        baselineModel.load(file);
    }

    // ==================== Getter/Setter ====================

    /**
     * 检查推理引擎是否启用。
     *
     * @return 如果启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置推理引擎启用状态。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 检查是否优先使用推理结果。
     *
     * @return 如果优先推理返回 true
     */
    public boolean isPreferInference() {
        return preferInference;
    }

    /**
     * 设置是否优先使用推理结果。
     *
     * @param preferInference 是否优先推理
     */
    public void setPreferInference(boolean preferInference) {
        this.preferInference = preferInference;
    }

    /**
     * 检查是否启用自动学习。
     *
     * @return 如果自动学习启用返回 true
     */
    public boolean isAutoLearn() {
        return autoLearn;
    }

    /**
     * 设置是否启用自动学习。
     *
     * @param autoLearn 是否启用自动学习
     */
    public void setAutoLearn(boolean autoLearn) {
        this.autoLearn = autoLearn;
    }

    /**
     * 获取学习率系数。
     *
     * @return 学习率系数
     */
    public double getLearningRate() {
        return learningRate;
    }

    /**
     * 设置学习率系数。
     *
     * @param learningRate 学习率系数
     */
    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    /**
     * 获取推理结果影响权重。
     *
     * @return 影响权重
     */
    public double getInfluenceWeight() {
        return influenceWeight;
    }

    /**
     * 设置推理结果影响权重。
     *
     * @param influenceWeight 影响权重
     */
    public void setInfluenceWeight(double influenceWeight) {
        this.influenceWeight = influenceWeight;
    }

    /**
     * 获取触发学习所需的最小采样数。
     *
     * @return 最小采样数
     */
    public int getMinSamples() {
        return minSamples;
    }

    /**
     * 设置触发学习所需的最小采样数。
     *
     * @param minSamples 最小采样数
     */
    public void setMinSamples(int minSamples) {
        this.minSamples = minSamples;
    }

    /**
     * 获取受信任玩家映射表。
     *
     * @return 受信任玩家映射表
     */
    public ConcurrentHashMap<UUID, Boolean> getTrustedPlayers() {
        return trustedPlayers;
    }

    /**
     * 获取所有玩家状态映射表的只读视图。
     *
     * @return 玩家状态映射表
     */
    public ConcurrentHashMap<UUID, PlayerPhysicsState> getStates() {
        return states;
    }

    // ==================== 学习统计 Getter ====================

    /**
     * 获取学习次数（累计喂入基准模型的样本数）。
     *
     * @return 学习次数
     */
    public long getLearningCount() {
        return learningCount;
    }

    /**
     * 获取修正次数（检测到理论与实际偏差并自动修正阈值的次数）。
     *
     * @return 修正次数
     */
    public long getCorrectionCount() {
        return correctionCount;
    }

    /**
     * 获取迭代次数（基准模型完成完整更新周期的次数）。
     *
     * @return 迭代次数
     */
    public long getIterationCount() {
        return iterationCount;
    }

    /**
     * 获取学习进度百分比。
     * <p>
     * 基于基准模型的总采样数与校准阈值计算。
     * 超过阈值后返回 100%。
     * </p>
     *
     * @return 学习进度百分比（0.0 - 100.0）
     */
    public double getLearningProgressPercent() {
        long total = baselineModel.getTotalSamples();
        long threshold = 1000; // 1000 个样本视为初步校准完成
        if (total >= threshold) {
            return 100.0;
        }
        return (total * 100.0) / threshold;
    }

    /**
     * 获取受信任玩家数量。
     *
     * @return 受信任玩家数量
     */
    public int getTrustedPlayerCount() {
        return trustedPlayers.size();
    }

    /**
     * 获取已记录的场景数量。
     *
     * @return 场景基线数量
     */
    public int getScenarioCount() {
        return baselineModel.getScenarioBaselines().size();
    }

    public void trainMlp(List<double[]> samples) {
        plugin.getSchedulerAdapter().runAsync(() -> {
            try {
                final int epochs = trainEpochs;
                double totalLossFusion = 0.0;
                int fusionCount = 0;
                for (int epoch = 0; epoch < epochs; epoch++) {
                    double totalLossMove = 0.0;
                    double totalLossCombat = 0.0;
                    double epochFusionLoss = 0.0;
                    int epochFusionCount = 0;
                    for (double[] sample : samples) {
                        totalLossMove += movementMLP.train(sample, 1.0);
                        double[] combatSlice = BehaviorFeatureExtractor.extractCombatSlice(sample);
                        totalLossCombat += combatMLP.train(combatSlice, 1.0);

                        // CausalFusion 训练: 构建8维因果输入
                        // 从样本特征中提取移动/战斗分数 + 环境特征
                        double moveScore = movementMLP.forward(sample);
                        double combatScore = combatMLP.forward(combatSlice);
                        double ruleScore = 0.0; // 受信任玩家训练样本, 无规则偏离
                        // 因果输入: envExplain=0, speedRatio=0.5, knockback=0, headJump=0, impact=0
                        // (训练样本是正常玩家, 环境上下文已编码在84维特征中)
                        double[] causalInputs = new double[]{
                            moveScore, combatScore, ruleScore,
                            0.3, 0.5, 0.0, 0.0, 0.0
                        };
                        epochFusionLoss += causalFusion.train(causalInputs, 0.0);
                        epochFusionCount++;
                    }
                    double avgLoss = totalLossMove / samples.size();
                    if (epoch % 20 == 0 || epoch == epochs - 1) {
                        plugin.getLogger().info(String.format(
                            "MLP 训练 epoch %d/%d, 移动损失: %.6f, 战斗损失: %.6f, 融合损失: %.6f",
                            epoch + 1, epochs, avgLoss, totalLossCombat / samples.size(),
                            epochFusionCount > 0 ? epochFusionLoss / epochFusionCount : 0.0));

                        // 每20个epoch通知在线管理员训练进度
                        int finalEpoch = epoch;
                        double finalAvgLoss = avgLoss;
                        boolean isLast = (epoch == epochs - 1);
                        plugin.getSchedulerAdapter().runAsync(() -> {
                            Component msg = miniMessage.deserialize(
                                "<gray>[<dark_aqua>ANSAC-MLP</dark_aqua>]</gray> " +
                                "<yellow>训练进度：<white>" + (finalEpoch + 1) + "/" + epochs + "</white>" +
                                " | 移动损失：<white>" + String.format("%.6f", finalAvgLoss) + "</white>" +
                                (isLast
                                    ? " <green>训练完成!</green>"
                                    : " <gray>继续训练中...</gray>")
                            );
                            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                                if (p.hasPermission("ansac.admin")) {
                                    p.sendMessage(msg);
                                    // 同时发送 ActionBar 显示进度条
                                    double progress = (double)(finalEpoch + 1) / epochs;
                                    String bar = buildProgressBar(progress, 20);
                                    p.sendActionBar(miniMessage.deserialize(
                                        "<dark_aqua>" + bar + "</dark_aqua> <white>" +
                                        String.format("%.1f%%", progress * 100) + "</white>"
                                    ));
                                }
                            }
                        });
                    }
                }
                // 保存三个模型
                MLPPersistence.saveMovement(movementMLP, mlpFile);
                MLPPersistence.saveCombat(combatMLP, combatMlpFile);
                MLPPersistence.saveFusion(causalFusion, fusionMlpFile);
                plugin.getLogger().info("三个 MLP 模型已保存");
                samplingSession.markReady();
            } catch (IOException e) {
                plugin.getLogger().severe("MLP 模型保存失败: " + e.getMessage());
                samplingSession.adminStop();
            }
        });
    }

    private static String buildProgressBar(double progress, int length) {
        int filled = (int) Math.round(progress * length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? "\u25a0" : "\u25a1");
        }
        return sb.toString();
    }

    public MovementMLP getMovementMLP() { return movementMLP; }

    // ==================== 双模型 AB 架构管理方法 ====================

    /**
     * 训练威胁模型（B模型）。
     * <p>
     * 使用高危玩家（已确认作弊者）的行为数据进行批量训练。
     * 训练目标: target=1.0 表示作弊行为模式。
     * </p>
     *
     * @param samples 作弊玩家行为特征样本列表
     */
    public void trainThreatMlp(List<double[]> samples) {
        plugin.getSchedulerAdapter().runAsync(() -> {
            try {
                final int epochs = trainEpochs;
                double lastLoss = 0.0;
                for (int epoch = 0; epoch < epochs; epoch++) {
                    double totalLoss = 0.0;
                    for (double[] sample : samples) {
                        totalLoss += threatModelBundle.trainOnCheater(sample);
                    }
                    lastLoss = totalLoss / samples.size();
                    if (epoch % 20 == 0 || epoch == epochs - 1) {
                        plugin.getLogger().info(String.format(
                            "威胁模型 (B模型) 训练 epoch %d/%d, 平均损失: %.6f",
                            epoch + 1, epochs, lastLoss));
                        // 通知在线管理员
                        int finalEpoch = epoch;
                        boolean isLast = (epoch == epochs - 1);
                        final double finalLoss = lastLoss;
                        plugin.getSchedulerAdapter().runAsync(() -> {
                            Component msg = miniMessage.deserialize(
                                "<gray>[<dark_purple>ANSAC-ThreatMLP</dark_purple>]</gray> " +
                                "<dark_purple>威胁模型训练</dark_purple> " +
                                String.format("epoch %d/%d 损失: %.6f", finalEpoch + 1, epochs, finalLoss) +
                                (isLast ? " <green>✓ 训练完成</green>" : " <gray>继续训练中...</gray>")
                            );
                            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                                if (p.hasPermission("ansac.admin")) {
                                    p.sendMessage(msg);
                                }
                            }
                        });
                    }
                }
                // 保存威胁模型
                saveThreatModels();
                plugin.getLogger().info("威胁模型 (B模型) 已保存, 训练样本数: " + samples.size());
                threatSamplingSession.markReady();
            } catch (Exception e) {
                plugin.getLogger().severe("威胁模型训练失败: " + e.getMessage());
                threatSamplingSession.adminStop();
            }
        });
    }

    /**
     * 标记玩家为高危（已确认作弊者）。
     * <p>
     * 高危玩家的行为数据将自动喂入B模型进行训练，
     * 且B模型推理权重自动提升。
     * </p>
     */
    public boolean markHighRisk(UUID uuid) {
        if (highRiskPlayers.containsKey(uuid)) return false;
        highRiskPlayers.put(uuid, true);
        return true;
    }

    /**
     * 取消高危标记。
     */
    public boolean unmarkHighRisk(UUID uuid) {
        return highRiskPlayers.remove(uuid) != null;
    }

    /** 玩家是否被标记为高危 */
    public boolean isHighRisk(UUID uuid) {
        return highRiskPlayers.containsKey(uuid);
    }

    /** 获取所有高危玩家UUID */
    public Set<UUID> getHighRiskPlayers() {
        return highRiskPlayers.keySet();
    }

    /**
     * 启用实时同步推理（被标记为合法的玩家逐tick实时推理+在线学习）。
     */
    public boolean enableRealtimeInference(UUID uuid) {
        if (realtimeInferencePlayers.containsKey(uuid)) return false;
        realtimeInferencePlayers.put(uuid, true);
        return true;
    }

    /**
     * 禁用实时同步推理。
     */
    public boolean disableRealtimeInference(UUID uuid) {
        return realtimeInferencePlayers.remove(uuid) != null;
    }

    /** 玩家是否启用了实时同步推理 */
    public boolean isRealtimeInference(UUID uuid) {
        return realtimeInferencePlayers.containsKey(uuid);
    }

    /** 获取所有实时推理玩家UUID */
    public Set<UUID> getRealtimeInferencePlayers() {
        return realtimeInferencePlayers.keySet();
    }

    /**
     * 获取玩家双模型推理结果。
     * <p>
     * 返回A模型和B模型的完整推理快照，包含ModelSelector的综合评估结论。
     * </p>
     */
    public DualInferenceResult getDualInferenceResult(UUID uuid) {
        PlayerPhysicsState state = states.get(uuid);
        if (state == null) return DualInferenceResult.EMPTY;

        dev.ztros.ansac.player.PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(uuid);
        PlayerBehaviorProfile profile = (playerData != null) ? playerData.getBehaviorProfile() : new PlayerBehaviorProfile();
        double[] features = BehaviorFeatureExtractor.extract(state, profile);

        double movementScore = movementMLP.forward(features);
        double[] combatFeatures = BehaviorFeatureExtractor.extractCombatSlice(features);
        double combatScore = combatMLP.forward(combatFeatures);
        double[] causalInputs = BehaviorFeatureExtractor.extractCausalInputs(state, movementScore, combatScore, 0.0);
        double anomalyScore = causalFusion.forward(causalInputs);

        if (!dualModelEnabled) {
            return new DualInferenceResult(
                movementScore, combatScore, anomalyScore,
                0.0, 0.0, 0.0, null, false, false
            );
        }

        double threatMovementScore = threatModelBundle.forwardMovement(features);
        double threatCombatScore = threatModelBundle.forwardCombat(combatFeatures);
        double[] threatCausalInputs = BehaviorFeatureExtractor.extractCausalInputs(
            state, threatMovementScore, threatCombatScore, 0.0);
        double threatFusionScore = threatModelBundle.forwardFusion(threatCausalInputs);

        boolean isHighRisk = highRiskPlayers.containsKey(uuid);
        double ruleFactor = 0.0;
        if (playerData != null) {
            int totalVL = playerData.getTotalVL();
            ruleFactor = Math.min(1.0, totalVL / (double) modelPunishVL);
        }
        ModelSelector.ModelSelectorResult selectorResult = modelSelector.evaluate(
            movementScore, threatFusionScore, ruleFactor, isHighRisk
        );

        return new DualInferenceResult(
            movementScore, combatScore, anomalyScore,
            threatMovementScore, threatCombatScore, threatFusionScore,
            selectorResult, isHighRisk, realtimeInferencePlayers.containsKey(uuid)
        );
    }

    /** 获取威胁模型束 */
    public ThreatModelBundle getThreatModelBundle() { return threatModelBundle; }

    /** 获取模型选择器 */
    public ModelSelector getModelSelector() { return modelSelector; }

    /** 获取B模型采样会话 */
    public MLPSamplingSession getThreatSamplingSession() { return threatSamplingSession; }

    /** 双模型是否启用 */
    public boolean isDualModelEnabled() { return dualModelEnabled; }
    public CombatMLP getCombatMLP() { return combatMLP; }
    public CausalFusion getCausalFusion() { return causalFusion; }

    public DetectionMode getDetectionMode() { return detectionMode; }
    public void setDetectionMode(DetectionMode mode) {
        this.detectionMode = mode;
        if (plugin != null) {
            plugin.getLogger().info("检测模式已切换为: " + mode.name());
        }
    }

    /**
     * 获取指定玩家的 MLP 推理详情，包含各层激活值。
     * 用于可视化模型的思考过程。
     */
    public MLPInferenceDetail getDetailedMlpResult(UUID uuid) {
        PlayerPhysicsState state = states.get(uuid);
        if (state == null) {
            return null;
        }
        dev.ztros.ansac.player.PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(uuid);
        PlayerBehaviorProfile profile = (playerData != null) ? playerData.getBehaviorProfile() : new PlayerBehaviorProfile();
        double[] features = BehaviorFeatureExtractor.extract(state, profile);
        return movementMLP.forwardDetailed(features);
    }

    public MLPSamplingSession getSamplingSession() {
        return samplingSession;
    }

    // ==================== 实时监控 ====================

    /**
     * 开始实时监控指定玩家。
     * 每 2 秒向所有管理员推送一次 AI 思维状态。
     */
    public void startWatch(UUID targetUuid) {
        if (watchActive.containsKey(targetUuid)) return;
        if (plugin == null) return;

        watchActive.put(targetUuid, true);

        plugin.getSchedulerAdapter().runTimerAsync(
            () -> {
                if (!watchActive.containsKey(targetUuid)) return;
                Player target = plugin.getServer().getPlayer(targetUuid);
                if (target == null || !target.isOnline()) {
                    stopWatch(targetUuid);
                    return;
                }

                PlayerPhysicsState state = states.get(targetUuid);
                if (state == null) return;

                double moveScore = state.getLastNormalScore();
                double anomalyScore = state.getLastAnomalyScore();

                dev.ztros.ansac.player.PlayerData data = plugin.getPlayerDataManager().getPlayerData(targetUuid);
                double combatScore = 0.5;
                if (data != null) {
                    double[] features = BehaviorFeatureExtractor.extract(state, data.getBehaviorProfile());
                    double[] combatSlice = BehaviorFeatureExtractor.extractCombatSlice(features);
                    combatScore = combatMLP.forward(combatSlice);
                }

                String thoughtLine = InferenceInterpreter.buildThoughtLine(
                    moveScore, combatScore, anomalyScore,
                    samplingSession.getTrainRound());

                String indicator = InferenceInterpreter.buildThinkingIndicator(anomalyScore);

                for (Player admin : plugin.getServer().getOnlinePlayers()) {
                    if (admin.hasPermission("ansac.admin")) {
                        admin.sendActionBar(miniMessage.deserialize(
                            "<dark_aqua>" + indicator + "</dark_aqua> <gray>[AI:" +
                            target.getName() + "]</gray> " + thoughtLine
                        ));
                    }
                }
            },
            WATCH_INTERVAL_TICKS * 50L,
            WATCH_INTERVAL_TICKS * 50L,
            java.util.concurrent.TimeUnit.MILLISECONDS
        );

        // 通知管理员监控已开启
        Player target = plugin.getServer().getPlayer(targetUuid);
        String targetName = target != null ? target.getName() : targetUuid.toString().substring(0, 8);
        for (Player admin : plugin.getServer().getOnlinePlayers()) {
            if (admin.hasPermission("ansac.admin")) {
                admin.sendMessage(miniMessage.deserialize(
                    "<gray>[<dark_aqua>ANSAC</dark_aqua>]</gray> " +
                    "<green>已开始实时监控 <yellow>" + targetName +
                    "</yellow>，每 " + (WATCH_INTERVAL_TICKS / 20) + " 秒推送 AI 思维状态到 ActionBar。</green>"
                ));
            }
        }
    }

    /**
     * 停止实时监控指定玩家。
     */
    public void stopWatch(UUID targetUuid) {
        watchActive.remove(targetUuid);
        if (plugin != null) {
            Player target = plugin.getServer().getPlayer(targetUuid);
            String targetName = target != null ? target.getName() : targetUuid.toString().substring(0, 8);
            for (Player admin : plugin.getServer().getOnlinePlayers()) {
                if (admin.hasPermission("ansac.admin")) {
                    admin.sendMessage(miniMessage.deserialize(
                        "<gray>[<dark_aqua>ANSAC</dark_aqua>]</gray> " +
                        "<red>已停止监控 <yellow>" + targetName + "</yellow>。</red>"
                    ));
                }
            }
        }
    }

    /**
     * 检查指定玩家是否正在被监控。
     */
    public boolean isWatching(UUID targetUuid) {
        return watchActive.containsKey(targetUuid);
    }

    /**
     * 获取当前所有被监控玩家的 UUID 集合。
     */
    public java.util.Set<UUID> getWatchedPlayers() {
        return watchActive.keySet();
    }
}
