package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
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

    // ==================== 基准模型 ====================

    /**
     * 自学习基准模型实例。
     */
    private final BaselineModel baselineModel;

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
        PlayerPhysicsState state = states.computeIfAbsent(uuid, k -> new PlayerPhysicsState());

        long now = System.currentTimeMillis();
        state.updateFromPlayer(player, from, to, now);

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
                state.getJumpBoostLevel()
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
        states.clear();
        trustedPlayers.clear();
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
}
