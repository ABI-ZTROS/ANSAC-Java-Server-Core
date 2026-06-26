package dev.ztros.ansac.physics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自学习基准模型。
 * <p>
 * 通过收集受信任玩家的真实移动数据，使用 EMA（指数移动平均）
 * 和 Welford 在线标准差算法持续学习各个场景下的典型速度模式。
 * 学习到的基准可用于更精确地识别异常移动。
 * </p>
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} 存储所有可变数据。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public class BaselineModel {

    // ==================== 场景基线记录 ====================

    /**
     * 场景基线数据记录。
     * <p>
     * 每个场景（如 "walk_ground", "sprint_ice" 等）维护独立的统计信息。
     * </p>
     *
     * @param scenarioName       场景名称标识符
     * @param learnedTypicalSpeed 通过 EMA 学习到的典型速度
     * @param maxObservedSpeed   历史最大观察速度
     * @param sampleCount        采样总数
     * @param m2                  Welford 算法的 M2 统计量（用于计算方差）
     * @param stdDev             当前在线标准差
     */
    public record ScenarioBaseline(
            String scenarioName,
            double learnedTypicalSpeed,
            double maxObservedSpeed,
            long sampleCount,
            double m2,
            double stdDev
    ) {}

    // ==================== 学习值 ====================

    /**
     * 通过自学习得到的行走速度。
     * <p>初始值 = {@link PhysicsConstants#BASE_WALK_SPEED}</p>
     */
    private volatile double learnedWalkSpeed;

    /**
     * 通过自学习得到的疾跑速度。
     * <p>初始值 = {@link PhysicsConstants#BASE_SPRINT_SPEED}</p>
     */
    private volatile double learnedSprintSpeed;

    /**
     * 通过自学习得到的跳跃初始速度。
     * <p>初始值 = {@link PhysicsConstants#JUMP_INITIAL_VELOCITY}</p>
     */
    private volatile double learnedJumpInitialVelocity;

    /**
     * 通过自学习得到的跳跃高度。
     * <p>初始值 = {@link PhysicsConstants#MAX_JUMP_HEIGHT_NO_BOOST}</p>
     */
    private volatile double learnedJumpHeight;

    /**
     * 通过自学习得到的疾跑跳跃距离。
     * <p>初始值 = {@link PhysicsConstants#MAX_SPRINT_JUMP_DISTANCE}</p>
     */
    private volatile double learnedSprintJumpDistance;

    /**
     * 通过自学习得到的重力加速度。
     * <p>初始值 = {@link PhysicsConstants#GRAVITY_ACCELERATION}</p>
     */
    private volatile double learnedGravityAccel;

    /**
     * 通过自学习得到的重力阻力系数。
     * <p>初始值 = {@link PhysicsConstants#GRAVITY_DRAG}</p>
     */
    private volatile double learnedGravityDrag;

    /**
     * 通过自学习得到的终端速度。
     * <p>初始值 = {@link PhysicsConstants#TERMINAL_VELOCITY}</p>
     */
    private volatile double learnedTerminalVelocity;

    // ==================== 场景基线 ====================

    /**
     * 场景基线映射表。
     * <p>键为场景标识符，值为该场景的基线统计数据。</p>
     */
    private final ConcurrentHashMap<String, ScenarioBaseline> scenarioBaselines;

    // ==================== 学习状态 ====================

    /**
     * 总采样数（所有场景累计）。
     */
    private volatile long totalSamples;

    /**
     * 是否已完成校准。
     * <p>当总采样数超过最小阈值时认为已校准。</p>
     */
    private volatile boolean isCalibrated;

    /**
     * 校准所需的最小采样数。
     */
    private static final long CALIBRATION_THRESHOLD = 100;

    /**
     * 创建自学习基准模型。
     * <p>所有学习值初始化为 PhysicsConstants 中的对应值。</p>
     */
    public BaselineModel() {
        this.learnedWalkSpeed = PhysicsConstants.BASE_WALK_SPEED;
        this.learnedSprintSpeed = PhysicsConstants.BASE_SPRINT_SPEED;
        this.learnedJumpInitialVelocity = PhysicsConstants.JUMP_INITIAL_VELOCITY;
        this.learnedJumpHeight = PhysicsConstants.MAX_JUMP_HEIGHT_NO_BOOST;
        this.learnedSprintJumpDistance = PhysicsConstants.MAX_SPRINT_JUMP_DISTANCE;
        this.learnedGravityAccel = PhysicsConstants.GRAVITY_ACCELERATION;
        this.learnedGravityDrag = PhysicsConstants.GRAVITY_DRAG;
        this.learnedTerminalVelocity = PhysicsConstants.TERMINAL_VELOCITY;
        this.scenarioBaselines = new ConcurrentHashMap<>();
        this.totalSamples = 0;
        this.isCalibrated = false;
    }

    // ==================== 核心学习方法 ====================

    /**
     * 记录一个场景采样值。
     * <p>
     * 使用 EMA（指数移动平均）更新场景的典型速度，
     * 同时使用 Welford 在线算法计算标准差。
     * </p>
     * <p>
     * EMA 公式: alpha = min(0.1, 2.0 / (count + 1))
     * <br>Welford 公式: delta = value - mean; mean += delta / count; m2 += delta * (value - mean); stdDev = sqrt(m2 / (count - 1))
     * </p>
     *
     * @param scenarioKey 场景标识符
     * @param value       采样值（通常是速度）
     */
    public void recordSample(String scenarioKey, double value) {
        if (scenarioKey == null || scenarioKey.isEmpty()) {
            return;
        }

        scenarioBaselines.compute(scenarioKey, (key, baseline) -> {
            if (baseline == null) {
                // 首次采样
                return new ScenarioBaseline(
                        key,
                        value,           // learnedTypicalSpeed = value
                        value,           // maxObservedSpeed = value
                        1,               // sampleCount = 1
                        0.0,             // m2 = 0（第一个样本没有方差）
                        0.0              // stdDev = 0
                );
            }

            long count = baseline.sampleCount() + 1;
            double oldMean = baseline.learnedTypicalSpeed();

            // Welford 在线算法: 更新均值
            double delta = value - oldMean;
            double newMean = oldMean + delta / count;

            // Welford 在线算法: 更新 M2 统计量
            double oldM2 = baseline.m2();
            double newM2 = oldM2 + delta * (value - newMean);

            // 计算在线标准差（至少 2 个样本）
            double stdDev = 0.0;
            if (count >= 2) {
                stdDev = Math.sqrt(newM2 / (count - 1));
            }

            // EMA 更新典型速度
            // alpha = min(0.1, 2.0 / (count + 1))
            double alpha = Math.min(0.1, 2.0 / (count + 1));
            double emaSpeed = oldMean + alpha * (value - oldMean);

            // 更新最大观察速度
            double maxObserved = Math.max(baseline.maxObservedSpeed(), value);

            return new ScenarioBaseline(
                    key,
                    emaSpeed,
                    maxObserved,
                    count,
                    newM2,
                    stdDev
            );
        });

        this.totalSamples++;
        this.isCalibrated = this.totalSamples >= CALIBRATION_THRESHOLD;
    }

    /**
     * 获取指定场景的学习速度。
     * <p>
     * 返回该场景通过 EMA 学习到的典型速度。
     * 如果场景不存在，返回 0。
     * </p>
     *
     * @param scenarioKey 场景标识符
     * @return 该场景的典型速度，如果不存在返回 0
     */
    public double getLearnedSpeedForScenario(String scenarioKey) {
        ScenarioBaseline baseline = scenarioBaselines.get(scenarioKey);
        return baseline != null ? baseline.learnedTypicalSpeed() : 0.0;
    }

    /**
     * 判断模型是否已完成校准。
     *
     * @return 如果总采样数超过阈值则返回 true
     */
    public boolean isCalibrated() {
        return isCalibrated;
    }

    // ==================== 重置 ====================

    /**
     * 重置所有学习数据到初始值。
     * <p>
     * 清除所有场景基线，将学习值恢复为 PhysicsConstants 中的值。
     * </p>
     */
    public void reset() {
        this.learnedWalkSpeed = PhysicsConstants.BASE_WALK_SPEED;
        this.learnedSprintSpeed = PhysicsConstants.BASE_SPRINT_SPEED;
        this.learnedJumpInitialVelocity = PhysicsConstants.JUMP_INITIAL_VELOCITY;
        this.learnedJumpHeight = PhysicsConstants.MAX_JUMP_HEIGHT_NO_BOOST;
        this.learnedSprintJumpDistance = PhysicsConstants.MAX_SPRINT_JUMP_DISTANCE;
        this.learnedGravityAccel = PhysicsConstants.GRAVITY_ACCELERATION;
        this.learnedGravityDrag = PhysicsConstants.GRAVITY_DRAG;
        this.learnedTerminalVelocity = PhysicsConstants.TERMINAL_VELOCITY;
        this.scenarioBaselines.clear();
        this.totalSamples = 0;
        this.isCalibrated = false;
    }

    // ==================== 持久化 ====================

    /**
     * 保存基准模型到 YAML 格式文件。
     * <p>
     * 将所有学习值和场景基线数据写入指定文件。
     * 文件格式为简化的 YAML，便于人工阅读和调试。
     * </p>
     *
     * @param file 目标文件
     * @throws IOException 如果写入文件失败
     */
    public void save(File file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write("# ANSAC BaselineModel - 自学习基准模型");
            writer.newLine();
            writer.write("# 自动生成，请勿手动修改");
            writer.newLine();
            writer.newLine();

            // 写入全局学习值
            writer.write("learned-values:");
            writer.newLine();
            writer.write("  walk-speed: " + learnedWalkSpeed);
            writer.newLine();
            writer.write("  sprint-speed: " + learnedSprintSpeed);
            writer.newLine();
            writer.write("  jump-initial-velocity: " + learnedJumpInitialVelocity);
            writer.newLine();
            writer.write("  jump-height: " + learnedJumpHeight);
            writer.newLine();
            writer.write("  sprint-jump-distance: " + learnedSprintJumpDistance);
            writer.newLine();
            writer.write("  gravity-accel: " + learnedGravityAccel);
            writer.newLine();
            writer.write("  gravity-drag: " + learnedGravityDrag);
            writer.newLine();
            writer.write("  terminal-velocity: " + learnedTerminalVelocity);
            writer.newLine();

            // 写入学习状态
            writer.write("learning-state:");
            writer.newLine();
            writer.write("  total-samples: " + totalSamples);
            writer.newLine();
            writer.write("  is-calibrated: " + isCalibrated);
            writer.newLine();

            // 写入场景基线
            writer.newLine();
            writer.write("scenario-baselines:");
            writer.newLine();
            for (ScenarioBaseline baseline : scenarioBaselines.values()) {
                writer.write("  \"" + baseline.scenarioName() + "\":");
                writer.newLine();
                writer.write("    typical-speed: " + baseline.learnedTypicalSpeed());
                writer.newLine();
                writer.write("    max-observed-speed: " + baseline.maxObservedSpeed());
                writer.newLine();
                writer.write("    sample-count: " + baseline.sampleCount());
                writer.newLine();
                writer.write("    m2: " + baseline.m2());
                writer.newLine();
                writer.write("    std-dev: " + baseline.stdDev());
                writer.newLine();
            }
        }
    }

    /**
     * 从 YAML 格式文件加载基准模型。
     * <p>
     * 读取之前通过 {@link #save(File)} 保存的文件，
     * 恢复所有学习值和场景基线数据。
     * </p>
     *
     * @param file 源文件
     * @throws IOException 如果读取文件失败
     */
    public void load(File file) throws IOException {
        if (!file.exists()) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            String section = null;
            String currentScenario = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // 跳过注释和空行
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // 检测段落切换
                if (line.equals("learned-values:")) {
                    section = "learned-values";
                    continue;
                } else if (line.equals("learning-state:")) {
                    section = "learning-state";
                    continue;
                } else if (line.equals("scenario-baselines:")) {
                    section = "scenario-baselines";
                    continue;
                }

                // 解析键值对
                switch (section) {
                    case "learned-values":
                        parseLearnedValue(line);
                        break;
                    case "learning-state":
                        parseLearningState(line);
                        break;
                    case "scenario-baselines":
                        currentScenario = parseScenarioBaseline(line, currentScenario);
                        break;
                }
            }
        }
    }

    /**
     * 解析学习值行。
     *
     * @param line YAML 行
     */
    private void parseLearnedValue(String line) {
        String[] parts = line.split(":", 2);
        if (parts.length != 2) return;
        String key = parts[0].trim();
        String value = parts[1].trim();

        try {
            double numValue = Double.parseDouble(value);
            switch (key) {
                case "walk-speed" -> learnedWalkSpeed = numValue;
                case "sprint-speed" -> learnedSprintSpeed = numValue;
                case "jump-initial-velocity" -> learnedJumpInitialVelocity = numValue;
                case "jump-height" -> learnedJumpHeight = numValue;
                case "sprint-jump-distance" -> learnedSprintJumpDistance = numValue;
                case "gravity-accel" -> learnedGravityAccel = numValue;
                case "gravity-drag" -> learnedGravityDrag = numValue;
                case "terminal-velocity" -> learnedTerminalVelocity = numValue;
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
    }

    /**
     * 解析学习状态行。
     *
     * @param line YAML 行
     */
    private void parseLearningState(String line) {
        String[] parts = line.split(":", 2);
        if (parts.length != 2) return;
        String key = parts[0].trim();
        String value = parts[1].trim();

        switch (key) {
            case "total-samples" -> {
                try { totalSamples = Long.parseLong(value); } catch (NumberFormatException e) { /* ignore */ }
            }
            case "is-calibrated" -> isCalibrated = Boolean.parseBoolean(value);
        }
    }

    /**
     * 解析场景基线行。
     *
     * @param line         YAML 行
     * @param currentName  当前场景名称（可为 null）
     * @return 更新后的当前场景名称
     */
    private String parseScenarioBaseline(String line, String currentName) {
        // 场景名称行: "scenario-name":
        if (line.startsWith("\"") && line.endsWith(":")) {
            return line.substring(1, line.length() - 2);
        }

        if (currentName == null) return null;

        // 解析场景属性
        String[] parts = line.split(":", 2);
        if (parts.length != 2) return currentName;
        String key = parts[0].trim();
        String value = parts[1].trim();

        ScenarioBaseline existing = scenarioBaselines.get(currentName);
        if (existing == null) return currentName;

        try {
            switch (key) {
                case "typical-speed" -> {
                    double v = Double.parseDouble(value);
                    scenarioBaselines.put(currentName, new ScenarioBaseline(
                            existing.scenarioName(), v, existing.maxObservedSpeed(),
                            existing.sampleCount(), existing.m2(), existing.stdDev()
                    ));
                }
                case "max-observed-speed" -> {
                    double v = Double.parseDouble(value);
                    scenarioBaselines.put(currentName, new ScenarioBaseline(
                            existing.scenarioName(), existing.learnedTypicalSpeed(), v,
                            existing.sampleCount(), existing.m2(), existing.stdDev()
                    ));
                }
                case "sample-count" -> {
                    long v = Long.parseLong(value);
                    scenarioBaselines.put(currentName, new ScenarioBaseline(
                            existing.scenarioName(), existing.learnedTypicalSpeed(), existing.maxObservedSpeed(),
                            v, existing.m2(), existing.stdDev()
                    ));
                }
                case "m2" -> {
                    double v = Double.parseDouble(value);
                    scenarioBaselines.put(currentName, new ScenarioBaseline(
                            existing.scenarioName(), existing.learnedTypicalSpeed(), existing.maxObservedSpeed(),
                            existing.sampleCount(), v, existing.stdDev()
                    ));
                }
                case "std-dev" -> {
                    double v = Double.parseDouble(value);
                    scenarioBaselines.put(currentName, new ScenarioBaseline(
                            existing.scenarioName(), existing.learnedTypicalSpeed(), existing.maxObservedSpeed(),
                            existing.sampleCount(), existing.m2(), v
                    ));
                }
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }

        return currentName;
    }

    // ==================== Getter 方法 ====================

    public double getLearnedWalkSpeed() { return learnedWalkSpeed; }
    public double getLearnedSprintSpeed() { return learnedSprintSpeed; }
    public double getLearnedJumpInitialVelocity() { return learnedJumpInitialVelocity; }
    public double getLearnedJumpHeight() { return learnedJumpHeight; }
    public double getLearnedSprintJumpDistance() { return learnedSprintJumpDistance; }
    public double getLearnedGravityAccel() { return learnedGravityAccel; }
    public double getLearnedGravityDrag() { return learnedGravityDrag; }
    public double getLearnedTerminalVelocity() { return learnedTerminalVelocity; }
    public ConcurrentHashMap<String, ScenarioBaseline> getScenarioBaselines() { return scenarioBaselines; }
    public long getTotalSamples() { return totalSamples; }
}
