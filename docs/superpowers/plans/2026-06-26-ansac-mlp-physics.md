# ANSAC MLP 物理行为分析与采样系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ANSAC 反作弊插件引入轻量可训练 MLP 神经网络，深度分析正常玩家移动行为，配套采样-通知-训练闭环与管理员交互命令，同时修复已识别的 24 处代码异味。

**Architecture:** 在 `physics.mlp` 包中实现纯 Java MLP（输入24维→隐藏层1(ReLU)→隐藏层2(ReLU)→输出Sigmoid），从 `PlayerPhysicsState` 提取归一化特征。`MLPSamplingSession` 管理受信任玩家数据采样，达标后通过 MiniMessage 聊天通知管理员，`ANSACCommand` 提供 `/ansac sampling [start|continue|stop]` 指令交互。

**Tech Stack:** Java 21, Gradle, JUnit 5, Mockito, Bukkit/Paper/Folia API, Adventure MiniMessage

---

## Summary

本计划为 Minecraft 反作弊插件 ANSAC 引入轻量多层感知机（MLP），部署于 `physics` 包内，用于深度分析正常玩家行为模式。MLP 从 `PlayerPhysicsState` 的 `MovementSample` 滑动窗口及当前状态中提取 24 维归一化特征，输出 `[0,1]` 区间的行为正常度评分。系统包含完整的可训练能力（前向传播 + 反向传播 SGD）、受信任玩家采样会话管理、MiniMessage 管理员交互提示、以及 `/ansac sampling` 命令体系。同时本计划对项目中已识别的 24 处代码异味提供精确修复方案与完整替换代码。

---

## Current State

- **物理系统**：`PhysicsInferenceService` 管理 `PlayerPhysicsState`、`BaselineModel`（EMA+Welford），提供 `InferenceResult` 供检查使用。
- **滑动窗口**：`PlayerPhysicsState` 维护最多 20 个 `MovementSample`，但仅用于基础统计，未进行深度模式学习。
- **命令体系**：`ANSACCommand` 已具备 trust/baseline/inference 子命令，缺少采样管理入口。
- **配置体系**：`ANSACConfig` 读取 `physics-inference` 配置节点，缺少 MLP 相关项。
- **测试体系**：项目当前无任何单元测试目录或依赖。

---

## Code Smell Audit（屎山代码扫描结果）

| # | 文件 | 行号 | 异味类型 | 严重度 | 描述 |
|---|------|------|----------|--------|------|
| 1 | `PacketListener.java` | 257-264 | O(n) 遍历 | 高 | `getEntityById` 遍历 `player.getWorld().getEntities()`，高密度实体场景性能极差。Bukkit 原生提供 O(1) 的 `getEntityById`。 |
| 2 | `PlayerData.java` | 全文件 | God Class | 中 | 50+ 字段混合移动/战斗/包/违规/buffer，违反 SRP，维护成本高。 |
| 3 | `CheckManager.java` | 157-173, 224-238 | 重复代码 | 中 | `startCheckTask` 与 `processPlayer` 中推理调用逻辑完全重复。 |
| 4 | `CheckManager.java` | 145-176 | O(n*m) 遍历 | 中 | 每 tick 遍历所有玩家 × 所有检测，随检测数量线性恶化。 |
| 5 | `SpeedCheck.java` / `FlyCheck.java` | `shouldSkip()` | 重复代码 | 低 | 两个类中 `shouldSkip` 方法逻辑 95% 重合。 |
| 6 | `SpeedCheck.java` | 51-62 | 硬编码魔法数字 | 低 | `LENIENCY`, `BUFFER_MAX`, `JUMP_DETECTION_DELTA_Y` 等未集中管理。 |
| 7 | `FlyCheck.java` | 73-247 | 过长方法 | 高 | `performCheck()` 超过 170 行，承担跳跃/悬停/上升/下落/高度 5 种检测职责。 |
| 8 | `FlyCheck.java` | 137-143 | 注释与代码不符 | 中 | 注释写 "3 ticks grace"，代码仅给 1 tick。 |
| 9 | `KillAuraCheck.java` | 46, 257 | COW List 滥用 | 中 | `CopyOnWriteArrayList` 用于高频写入场景，每次写入全量复制，GC 压力大。 |
| 10 | `KillAuraCheck.java` | 136, 176-177 | O(n) 清理 + 冗余复制 | 中 | `removeIf` 全量遍历时间戳；`subList` 后又包装新 COW List。 |
| 11 | `PhysicsInferenceService.java` | 227-279 | 字符串拼接热点 | 中 | `determineScenario()` 每次移动都 `StringBuilder` 拼接，场景键无上限增长。 |
| 12 | `BaselineModel.java` | 160-215 | 统计量混淆 | 中 | `recordSample` 中 Welford 均值 `newMean` 被丢弃，实际存储的是 EMA 值，两者数学定义不同。 |
| 13 | `BaselineModel.java` | 56-98 | 死字段 | 低 | `learnedWalkSpeed` 等 8 个 `volatile` 字段仅在构造/reset 中赋值，从未被 `recordSample` 更新。 |
| 14 | `BaselineModel.java` | 342-502 | 脆弱 YAML 解析 | 中 | 手写 YAML 解析器，场景基线加载时 `existing == null` 直接跳过，无法正确恢复新场景。 |
| 15 | `PlayerListener.java` | 144-158 | 重复 O(n) 查找 | 中 | 每次伤害事件 3 次 `getCheck()` 字符串线性查找。 |
| 16 | `PlayerListener.java` | 全文件 | 过长类 | 低 | 单一类处理 9 种事件类型。 |
| 17 | `ANSACCommand.java` | 20-589 | 过长类 | 低 | 单一类处理 13+ 子命令，600+ 行。 |
| 18 | `PingCompensator.java` | 67-73 | 除法精度风险 | 低 | `avgPing / 1000.0` 虽然此处无 bug，但风格上 int 先参与除法有风险。 |
| 19 | `ViolationData.java` | 21 | AtomicReference<Double> GC 压力 | 低 | 每次 `updateAndGet` 创建新 `Double` 对象，高并发下 GC 负担重。 |
| 20 | `PhysicsConstants.java` | 13-19 | 注释数据错误 | 中 | 注释声称蓝冰倍率 16.85x、冰面 9.27x，与代码常量 1.6/1.4 严重不符。 |
| 21 | `PlayerPhysicsState.java` | 391-395 | 硬编码 Material | 低 | 爬梯检测手写 5 种 `Material`，应使用 `Tag.CLIMBABLE`。 |
| 22 | `PlayerPhysicsState.java` | 420-422 | 逻辑恒真 | 高 | `jumpPhase != NONE || jumpPhase != LANDED` 恒为 `true`，导致 `wasOnGround` 逻辑永远走第一条分支。 |
| 23 | `PlayerPhysicsState.java` | 388 | 错误 Material | **极高** | `onBlueIce` 被赋值为 `Material.PACKED_ICE`（浮冰），蓝冰应为 `Material.BLUE_ICE`。 |
| 24 | `PhysicsEngine.java` | 155 | 速度药水公式错误 | **极高** | `baseSpeed += level * 0.2` 为加法，正确应为乘法 `baseSpeed *= (1 + level * 0.2)`。 |

---

## Proposed Changes

### Task 1: 新增 MLP 核心网络类

**Files:**
- Create: `src/main/java/dev/ztros/ansac/physics/mlp/MovementMLP.java`

- [ ] **Step 1: 编写 MovementMLP.java**

```java
package dev.ztros.ansac.physics.mlp;

import java.util.Random;

/**
 * 轻量多层感知机（MLP），用于分析玩家移动行为正常度。
 * 纯 Java 实现，无外部神经网络库依赖。
 *
 * <p>网络结构：输入层 -> 隐藏层1(ReLU) -> 隐藏层2(ReLU) -> 输出层(Sigmoid)</p>
 * <p>支持单样本在线训练（SGD）。</p>
 */
public final class MovementMLP {
    private static final long SEED = 0x4E5341434D4C5000L;

    private final int inputSize;
    private final int hidden1Size;
    private final int hidden2Size;
    private final double learningRate;

    private final double[][] w1;
    private final double[][] w2;
    private final double[] w3;

    private final double[] b1;
    private final double[] b2;
    private double b3;

    public MovementMLP(int inputSize, int hidden1Size, int hidden2Size, double learningRate) {
        if (inputSize <= 0 || hidden1Size <= 0 || hidden2Size <= 0) {
            throw new IllegalArgumentException("Layer sizes must be positive");
        }
        this.inputSize = inputSize;
        this.hidden1Size = hidden1Size;
        this.hidden2Size = hidden2Size;
        this.learningRate = learningRate;

        this.w1 = new double[inputSize][hidden1Size];
        this.w2 = new double[hidden1Size][hidden2Size];
        this.w3 = new double[hidden2Size];

        this.b1 = new double[hidden1Size];
        this.b2 = new double[hidden2Size];
        this.b3 = 0.0;

        xavierInit(w1, inputSize);
        xavierInit(w2, hidden1Size);
        xavierInit(w3, hidden2Size);
    }

    private void xavierInit(double[][] matrix, int fanIn) {
        Random rand = new Random(SEED ^ matrix.length ^ matrix[0].length);
        double limit = Math.sqrt(6.0 / fanIn);
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                matrix[i][j] = (rand.nextDouble() * 2.0 - 1.0) * limit;
            }
        }
    }

    private void xavierInit(double[] vector, int fanIn) {
        Random rand = new Random(SEED ^ vector.length);
        double limit = Math.sqrt(6.0 / fanIn);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (rand.nextDouble() * 2.0 - 1.0) * limit;
        }
    }

    public double forward(double[] input) {
        if (input == null || input.length != inputSize) {
            throw new IllegalArgumentException("Input size mismatch: expected " + inputSize
                + ", got " + (input == null ? "null" : input.length));
        }

        double[] h1 = new double[hidden1Size];
        for (int j = 0; j < hidden1Size; j++) {
            double sum = b1[j];
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * w1[i][j];
            }
            h1[j] = relu(sum);
        }

        double[] h2 = new double[hidden2Size];
        for (int j = 0; j < hidden2Size; j++) {
            double sum = b2[j];
            for (int i = 0; i < hidden1Size; i++) {
                sum += h1[i] * w2[i][j];
            }
            h2[j] = relu(sum);
        }

        double sum = b3;
        for (int i = 0; i < hidden2Size; i++) {
            sum += h2[i] * w3[i];
        }
        return sigmoid(sum);
    }

    public double train(double[] input, double target) {
        if (input == null || input.length != inputSize) {
            throw new IllegalArgumentException("Input size mismatch");
        }

        double[] h1Pre = new double[hidden1Size];
        double[] h1Post = new double[hidden1Size];
        for (int j = 0; j < hidden1Size; j++) {
            double sum = b1[j];
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * w1[i][j];
            }
            h1Pre[j] = sum;
            h1Post[j] = relu(sum);
        }

        double[] h2Pre = new double[hidden2Size];
        double[] h2Post = new double[hidden2Size];
        for (int j = 0; j < hidden2Size; j++) {
            double sum = b2[j];
            for (int i = 0; i < hidden1Size; i++) {
                sum += h1Post[i] * w2[i][j];
            }
            h2Pre[j] = sum;
            h2Post[j] = relu(sum);
        }

        double outPre = b3;
        for (int i = 0; i < hidden2Size; i++) {
            outPre += h2Post[i] * w3[i];
        }
        double outPost = sigmoid(outPre);

        double error = target - outPost;
        double loss = 0.5 * error * error;

        double dOut = error * sigmoidDerivative(outPre);

        double[] dH2 = new double[hidden2Size];
        for (int i = 0; i < hidden2Size; i++) {
            dH2[i] = dOut * w3[i] * reluDerivative(h2Pre[i]);
        }

        double[] dH1 = new double[hidden1Size];
        for (int i = 0; i < hidden1Size; i++) {
            double sum = 0.0;
            for (int j = 0; j < hidden2Size; j++) {
                sum += dH2[j] * w2[i][j];
            }
            dH1[i] = sum * reluDerivative(h1Pre[i]);
        }

        for (int i = 0; i < hidden2Size; i++) {
            w3[i] += learningRate * dOut * h2Post[i];
        }
        b3 += learningRate * dOut;

        for (int i = 0; i < hidden1Size; i++) {
            for (int j = 0; j < hidden2Size; j++) {
                w2[i][j] += learningRate * dH2[j] * h1Post[i];
            }
        }
        for (int j = 0; j < hidden2Size; j++) {
            b2[j] += learningRate * dH2[j];
        }

        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hidden1Size; j++) {
                w1[i][j] += learningRate * dH1[j] * input[i];
            }
        }
        for (int j = 0; j < hidden1Size; j++) {
            b1[j] += learningRate * dH1[j];
        }

        return loss;
    }

    private static double sigmoid(double x) {
        if (x >= 0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        } else {
            double z = Math.exp(x);
            return z / (1.0 + z);
        }
    }

    private static double sigmoidDerivative(double preActivation) {
        double s = sigmoid(preActivation);
        return s * (1.0 - s);
    }

    private static double relu(double x) {
        return Math.max(0.0, x);
    }

    private static double reluDerivative(double preActivation) {
        return preActivation > 0.0 ? 1.0 : 0.0;
    }

    public int getInputSize() { return inputSize; }
    public int getHidden1Size() { return hidden1Size; }
    public int getHidden2Size() { return hidden2Size; }
    public double getLearningRate() { return learningRate; }
    public double[][] getW1() { return w1; }
    public double[][] getW2() { return w2; }
    public double[] getW3() { return w3; }
    public double[] getB1() { return b1; }
    public double[] getB2() { return b2; }
    public double getB3() { return b3; }
    public void setB3(double b3) { this.b3 = b3; }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 2: 新增 MLP 持久化类

**Files:**
- Create: `src/main/java/dev/ztros/ansac/physics/mlp/MLPPersistence.java`

- [ ] **Step 1: 编写 MLPPersistence.java**

```java
package dev.ztros.ansac.physics.mlp;

import java.io.*;

public final class MLPPersistence {
    private static final int FILE_VERSION = 1;

    private MLPPersistence() {
        throw new UnsupportedOperationException();
    }

    public static void save(MovementMLP mlp, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(mlp.getInputSize());
            out.writeInt(mlp.getHidden1Size());
            out.writeInt(mlp.getHidden2Size());
            out.writeDouble(mlp.getLearningRate());

            double[][] w1 = mlp.getW1();
            for (int i = 0; i < w1.length; i++) {
                for (int j = 0; j < w1[i].length; j++) {
                    out.writeDouble(w1[i][j]);
                }
            }

            double[] b1 = mlp.getB1();
            for (double v : b1) out.writeDouble(v);

            double[][] w2 = mlp.getW2();
            for (int i = 0; i < w2.length; i++) {
                for (int j = 0; j < w2[i].length; j++) {
                    out.writeDouble(w2[i][j]);
                }
            }

            double[] b2 = mlp.getB2();
            for (double v : b2) out.writeDouble(v);

            double[] w3 = mlp.getW3();
            for (double v : w3) out.writeDouble(v);

            out.writeDouble(mlp.getB3());
        }
    }

    public static MovementMLP load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) {
                throw new IOException("Unsupported MLP file version: " + version);
            }
            int inputSize = in.readInt();
            int hidden1Size = in.readInt();
            int hidden2Size = in.readInt();
            double learningRate = in.readDouble();

            MovementMLP mlp = new MovementMLP(inputSize, hidden1Size, hidden2Size, learningRate);

            double[][] w1 = mlp.getW1();
            for (int i = 0; i < w1.length; i++) {
                for (int j = 0; j < w1[i].length; j++) {
                    w1[i][j] = in.readDouble();
                }
            }

            double[] b1 = mlp.getB1();
            for (int j = 0; j < b1.length; j++) {
                b1[j] = in.readDouble();
            }

            double[][] w2 = mlp.getW2();
            for (int i = 0; i < w2.length; i++) {
                for (int j = 0; j < w2[i].length; j++) {
                    w2[i][j] = in.readDouble();
                }
            }

            double[] b2 = mlp.getB2();
            for (int j = 0; j < b2.length; j++) {
                b2[j] = in.readDouble();
            }

            double[] w3 = mlp.getW3();
            for (int i = 0; i < w3.length; i++) {
                w3[i] = in.readDouble();
            }

            mlp.setB3(in.readDouble());
            return mlp;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 3: 新增特征提取器

**Files:**
- Create: `src/main/java/dev/ztros/ansac/physics/mlp/MLPFeatureExtractor.java`

- [ ] **Step 1: 编写 MLPFeatureExtractor.java**

```java
package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;

/**
 * 从 PlayerPhysicsState 提取归一化特征向量，供 MLP 使用。
 */
public final class MLPFeatureExtractor {
    public static final int FEATURE_COUNT = 24;

    private MLPFeatureExtractor() {
        throw new UnsupportedOperationException();
    }

    public static double[] extract(PlayerPhysicsState state) {
        if (state == null) {
            return new double[FEATURE_COUNT];
        }

        double[] f = new double[FEATURE_COUNT];
        int i = 0;

        double hSpeed = Math.sqrt(
            state.getVelocityX() * state.getVelocityX()
            + state.getVelocityZ() * state.getVelocityZ()
        );
        f[i++] = clamp(hSpeed / 2.0, 0.0, 1.0);
        f[i++] = clamp(state.getVelocityY(), -1.0, 1.0);
        f[i++] = clamp(state.getPredictedVelocityY(), -1.0, 1.0);
        f[i++] = clamp(state.getSpeedPotionLevel() / 5.0, 0.0, 1.0);
        f[i++] = clamp(state.getJumpBoostLevel() / 5.0, 0.0, 1.0);
        f[i++] = state.getJumpPhase().ordinal() / 5.0;
        f[i++] = clamp(state.getJumpTickCount() / 30.0, 0.0, 1.0);
        f[i++] = clamp(state.getTicksSinceLeftGround() / 100.0, 0.0, 1.0);
        f[i++] = clamp(state.getServerFallDistance() / 50.0, 0.0, 1.0);
        f[i++] = state.isClientOnGround() ? 1.0 : 0.0;
        f[i++] = state.isInWater() ? 1.0 : 0.0;
        f[i++] = state.isInLava() ? 1.0 : 0.0;
        f[i++] = state.isSneaking() ? 1.0 : 0.0;
        f[i++] = state.isSprinting() ? 1.0 : 0.0;
        f[i++] = state.isBlocking() ? 1.0 : 0.0;
        f[i++] = state.isOnIce() ? 1.0 : 0.0;
        f[i++] = state.isOnBlueIce() ? 1.0 : 0.0;
        f[i++] = state.hasLevitation() ? 1.0 : 0.0;
        f[i++] = state.hasSlowFalling() ? 1.0 : 0.0;
        f[i++] = state.hasDolphinsGrace() ? 1.0 : 0.0;
        f[i++] = state.hasSoulSpeed() ? 1.0 : 0.0;

        var samples = state.getMovementSamples();
        if (!samples.isEmpty()) {
            double avgH = 0.0;
            double avgV = 0.0;
            double varV = 0.0;
            int groundCount = 0;
            int n = samples.size();

            for (var s : samples) {
                avgH += s.horizontalSpeed();
                avgV += s.deltaY();
                if (s.onGround()) groundCount++;
            }
            avgH /= n;
            avgV /= n;

            for (var s : samples) {
                double d = s.deltaY() - avgV;
                varV += d * d;
            }
            varV = Math.sqrt(varV / n);

            f[i++] = clamp(avgH / 2.0, 0.0, 1.0);
            f[i++] = clamp(varV / 0.5, 0.0, 1.0);
            f[i++] = groundCount / (double) n;
        } else {
            f[i++] = 0.0;
            f[i++] = 0.0;
            f[i++] = 0.0;
        }

        while (i < FEATURE_COUNT) {
            f[i++] = 0.0;
        }

        return f;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 4: 新增采样会话管理器

**Files:**
- Create: `src/main/java/dev/ztros/ansac/physics/mlp/MLPSamplingSession.java`

- [ ] **Step 1: 编写 MLPSamplingSession.java**

```java
package dev.ztros.ansac.physics.mlp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * MLP 训练采样会话管理器。
 * 负责收集受信任玩家的特征向量，并在达到目标样本数后通知管理员决策。
 */
public final class MLPSamplingSession {
    public enum State {
        IDLE, COLLECTING, WAITING_ADMIN, TRAINING, READY
    }

    private final int targetSamples;
    private final List<double[]> samples = new ArrayList<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private State state = State.IDLE;

    public MLPSamplingSession(int targetSamples) {
        if (targetSamples <= 0) {
            throw new IllegalArgumentException("targetSamples must be positive");
        }
        this.targetSamples = targetSamples;
    }

    public synchronized boolean offerSample(double[] features) {
        if (state != State.COLLECTING) {
            return false;
        }
        samples.add(features.clone());
        if (samples.size() >= targetSamples) {
            state = State.WAITING_ADMIN;
            notifyAdmins();
            return true;
        }
        return false;
    }

    private void notifyAdmins() {
        Component message = miniMessage.deserialize(
            "<gray>[<dark_aqua>ANSAC-采样</dark_aqua>]</gray> " +
            "<green>受信任玩家移动数据采样已完成 (<white>" + samples.size() + "</white> 条)。</green> " +
            "请输入 <click:run_command:/ansac sampling continue><yellow><bold>/ansac sampling continue</bold></yellow></click> " +
            "开始训练 MLP 模型，或输入 " +
            "<click:run_command:/ansac sampling stop><red><bold>/ansac sampling stop</bold></red></click> 丢弃数据。"
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("ansac.admin")) {
                p.sendMessage(message);
            }
        }
    }

    public synchronized void startCollecting() {
        samples.clear();
        state = State.COLLECTING;
    }

    public synchronized void adminContinue() {
        if (state != State.WAITING_ADMIN) {
            return;
        }
        state = State.TRAINING;
    }

    public synchronized void adminStop() {
        if (state != State.WAITING_ADMIN && state != State.COLLECTING) {
            return;
        }
        samples.clear();
        state = State.IDLE;
    }

    public synchronized void markReady() {
        if (state == State.TRAINING) {
            state = State.READY;
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getSampleCount() {
        return samples.size();
    }

    public synchronized int getTargetSamples() {
        return targetSamples;
    }

    public synchronized List<double[]> drainSamples() {
        List<double[]> copy = new ArrayList<>(samples);
        samples.clear();
        return copy;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 5: 修改 PlayerPhysicsState（扩展 MLP 评分字段）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/PlayerPhysicsState.java`

- [ ] **Step 1: 添加 lastNormalScore 字段**

在 `// ==================== 时间追踪 ====================` 之前添加：

```java
    // ==================== MLP 推理评分 ====================
    /** MLP 推理的上一 tick 正常度评分 (0-1)，0.5 为未知/中性 */
    private double lastNormalScore = 0.5;
```

- [ ] **Step 2: 在 reset() 中重置该字段**

在 `reset()` 方法的 `this.lastKnockbackTime = 0;` 之后添加：

```java
        this.lastNormalScore = 0.5;
```

- [ ] **Step 3: 添加 Getter/Setter**

在类末尾的 Getter/Setter 区域添加：

```java
    public double getLastNormalScore() { return lastNormalScore; }
    public void setLastNormalScore(double lastNormalScore) { this.lastNormalScore = lastNormalScore; }
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 6: 修改 InferenceResult（扩展 normalScore）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/InferenceResult.java`

- [ ] **Step 1: 在 record 参数列表追加 normalScore**

在 `int jumpBoostLevel` 后追加：

```java
        double normalScore
```

- [ ] **Step 2: 更新 EMPTY 实例**

将 `EMPTY` 更新为：

```java
    public static final InferenceResult EMPTY = new InferenceResult(
            null,
            0.0, 0.0, 0.0, 0.0,
            false, false,
            false, false, false, false, false,
            false, false, false, false,
            PlayerPhysicsState.JumpPhase.NONE,
            0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0,
            0, 0,
            0.5
    );
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 7: 修改 PhysicsInferenceService（集成 MLP 与采样）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/PhysicsInferenceService.java`

- [ ] **Step 1: 添加 import**

在文件顶部添加：

```java
import dev.ztros.ansac.physics.mlp.MLPFeatureExtractor;
import dev.ztros.ansac.physics.mlp.MLPPersistence;
import dev.ztros.ansac.physics.mlp.MLPSamplingSession;
import dev.ztros.ansac.physics.mlp.MovementMLP;
```

- [ ] **Step 2: 添加 MLP 字段**

在 `// ==================== 基准模型 ====================` 后添加：

```java
    // ==================== MLP 模型 ====================

    private final MovementMLP movementMLP;
    private final MLPSamplingSession samplingSession;
    private final File mlpFile;
    private volatile boolean mlpEnabled;
```

- [ ] **Step 3: 修改构造函数**

在 `this.minSamples = 10;` 之后添加：

```java
        this.mlpFile = new File(plugin.getDataFolder(), "mlp-model.bin");
        int samplingTarget = plugin.getAnsacConfig().getMlpSamplingTarget();
        this.samplingSession = new MLPSamplingSession(samplingTarget);
        this.movementMLP = loadOrCreateMlp();
        this.mlpEnabled = plugin.getAnsacConfig().isMlpEnabled();
```

- [ ] **Step 4: 添加 loadOrCreateMlp 方法**

在构造函数后添加：

```java
    private MovementMLP loadOrCreateMlp() {
        if (mlpFile.exists()) {
            try {
                return MLPPersistence.load(mlpFile);
            } catch (IOException e) {
                if (plugin != null) {
                    plugin.getLogger().warning("加载 MLP 模型失败，将创建新模型: " + e.getMessage());
                }
            }
        }
        return new MovementMLP(MLPFeatureExtractor.FEATURE_COUNT, 16, 8, 0.01);
    }
```

- [ ] **Step 5: 在 onPlayerMove 中插入 MLP 推理**

在 `onPlayerMove(Player, Location, Location)` 方法中，在 `state.updateFromPlayer(player, from, to, now);` 之后、`// 自动学习` 之前插入：

```java
        // MLP 推理
        if (mlpEnabled) {
            double[] features = MLPFeatureExtractor.extract(state);
            double normalScore = movementMLP.forward(features);
            state.setLastNormalScore(normalScore);
        }
```

- [ ] **Step 6: 在自动学习块中插入 MLP 采样**

在 `// 自动学习` 块内的末尾（`baselineModel.recordSample(...)` 之后）插入：

```java
            // MLP 采样
            if (samplingSession.getState() == MLPSamplingSession.State.COLLECTING) {
                double[] features = MLPFeatureExtractor.extract(state);
                samplingSession.offerSample(features);
            }
```

- [ ] **Step 7: 更新 getInferenceResult**

在 `getInferenceResult(UUID)` 的 return 语句中，在最后一个参数 `state.getJumpBoostLevel()` 后追加：

```java
                state.getLastNormalScore()
```

- [ ] **Step 8: 添加公共方法**

在类末尾添加：

```java
    public void trainMlp(List<double[]> samples) {
        plugin.getSchedulerAdapter().runAsync(() -> {
            try {
                final int epochs = 200;
                for (int epoch = 0; epoch < epochs; epoch++) {
                    double totalLoss = 0.0;
                    for (double[] sample : samples) {
                        totalLoss += movementMLP.train(sample, 1.0);
                    }
                    if (epoch % 20 == 0 || epoch == epochs - 1) {
                        plugin.getLogger().info(String.format(
                            "MLP 训练 epoch %d/%d, 平均损失: %.6f",
                            epoch + 1, epochs, totalLoss / samples.size()));
                    }
                }
                MLPPersistence.save(movementMLP, mlpFile);
                plugin.getLogger().info("MLP 模型已保存至 " + mlpFile.getName());
                samplingSession.markReady();
            } catch (IOException e) {
                plugin.getLogger().severe("MLP 模型保存失败: " + e.getMessage());
                samplingSession.adminStop();
            }
        });
    }

    public MovementMLP getMovementMLP() {
        return movementMLP;
    }

    public MLPSamplingSession getSamplingSession() {
        return samplingSession;
    }

    public boolean isMlpEnabled() {
        return mlpEnabled;
    }

    public void setMlpEnabled(boolean mlpEnabled) {
        this.mlpEnabled = mlpEnabled;
    }
```

- [ ] **Step 9: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 8: 修改 ANSACCommand（添加 sampling 子命令）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/ANSACCommand.java`

- [ ] **Step 1: 添加 import**

```java
import dev.ztros.ansac.physics.mlp.MLPSamplingSession;
```

- [ ] **Step 2: 在 switch 中添加 case**

在 `case "inference":` 之后添加：

```java
            case "sampling":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleSamplingStatus(sender);
                    return true;
                }
                handleSamplingSub(sender, args[1]);
                break;
```

- [ ] **Step 3: 在 sendHelp 中添加帮助文本**

在 inference 帮助行之后添加：

```java
        sender.sendMessage(
            Component.text("/ansac sampling [start|continue|stop]", NamedTextColor.YELLOW)
                .append(Component.text(" - MLP 采样与训练管理", NamedTextColor.GRAY))
        );
```

- [ ] **Step 4: 添加 handleSamplingStatus 和 handleSamplingSub 方法**

在类末尾添加：

```java
    private void handleSamplingStatus(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        MLPSamplingSession session = svc.getSamplingSession();
        sender.sendMessage(Component.text("=== MLP 采样状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("会话状态：", NamedTextColor.YELLOW)
            .append(Component.text(session.getState().name(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("样本进度：", NamedTextColor.YELLOW)
            .append(Component.text(session.getSampleCount() + " / " + session.getTargetSamples(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("MLP 启用：", NamedTextColor.YELLOW)
            .append(Component.text(svc.isMlpEnabled() ? "是" : "否", NamedTextColor.WHITE)));
    }

    private void handleSamplingSub(CommandSender sender, String sub) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        MLPSamplingSession session = svc.getSamplingSession();
        switch (sub.toLowerCase()) {
            case "start":
                session.startCollecting();
                sender.sendMessage(Component.text("已开始收集受信任玩家的移动数据用于 MLP 训练。", NamedTextColor.GREEN));
                break;
            case "continue":
                if (session.getState() != MLPSamplingSession.State.WAITING_ADMIN) {
                    sender.sendMessage(Component.text("当前没有待处理的采样数据。", NamedTextColor.RED));
                    return;
                }
                java.util.List<double[]> samples = session.drainSamples();
                session.adminContinue();
                sender.sendMessage(Component.text("开始训练 MLP 模型，样本数：" + samples.size(), NamedTextColor.GREEN));
                svc.trainMlp(samples);
                sender.sendMessage(Component.text("MLP 训练任务已提交（异步执行中）。", NamedTextColor.GREEN));
                break;
            case "stop":
                session.adminStop();
                sender.sendMessage(Component.text("已停止采样并丢弃所有样本。", NamedTextColor.YELLOW));
                break;
            default:
                sender.sendMessage(Component.text("未知子命令。用法: /ansac sampling [start|continue|stop]", NamedTextColor.RED));
                break;
        }
    }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 9: 修改 ANSACConfig（添加 MLP 配置）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/config/ANSACConfig.java`

- [ ] **Step 1: 添加字段**

```java
    @Getter
    private boolean mlpEnabled;
    @Getter
    private int mlpSamplingTarget;
```

- [ ] **Step 2: 在 load() 中读取配置**

在 `load()` 方法末尾添加：

```java
        this.mlpEnabled = config.getBoolean("physics-inference.mlp-enabled", true);
        this.mlpSamplingTarget = config.getInt("physics-inference.mlp-sampling-target", 5000);
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 10: 修改 config.yml（添加 MLP 配置节点）

**Files:**
- Modify: `src/main/resources/config.yml`

- [ ] **Step 1: 在 physics-inference 节点追加**

在 `save-interval-minutes: 30` 之后追加：

```yaml
  # MLP 行为分析模型
  mlp-enabled: true
  mlp-sampling-target: 5000
```

---

### Task 11: 修改 ANSACPlugin（同步 MLP 配置）

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/ANSACPlugin.java`

- [ ] **Step 1: 在 syncPhysicsInferenceConfig 末尾添加**

```java
        physicsInferenceService.setMlpEnabled(ansacConfig.isMlpEnabled());
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 12: 修复关键 Bug（Code Smell Fixes）

#### Fix 1: PacketListener O(n) -> O(1)

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/listeners/PacketListener.java`

- [ ] **Step 1: 替换 getEntityById 方法**

替换第 257-264 行为：

```java
    private Entity getEntityById(Player player, int entityId) {
        return player.getWorld().getEntityById(entityId);
    }
```

#### Fix 2: PhysicsEngine 速度药水公式

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/PhysicsEngine.java`

- [ ] **Step 1: 替换速度药水加成逻辑**

替换第 154-155 行为：

```java
        // 速度药水加成
        if (state.getSpeedPotionLevel() > 0) {
            baseSpeed *= (1.0 + state.getSpeedPotionLevel() * PhysicsConstants.SPEED_POTION_PER_LEVEL);
        }
```

#### Fix 3: PlayerPhysicsState 蓝冰 Material

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/PlayerPhysicsState.java`

- [ ] **Step 1: 替换第 388 行**

```java
        this.onBlueIce = (belowType == Material.BLUE_ICE);
```

#### Fix 4: PlayerPhysicsState 跳跃状态机逻辑恒真

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/physics/PlayerPhysicsState.java`

- [ ] **Step 1: 替换第 420-422 行**

```java
        boolean wasOnGround = (jumpPhase != JumpPhase.NONE && jumpPhase != JumpPhase.LANDED)
                ? ticksSinceLeftGround == 0
                : clientOnGround;
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 13: 添加测试依赖与单元测试

**Files:**
- Modify: `build.gradle`
- Create: `src/test/java/dev/ztros/ansac/physics/mlp/MovementMLPTest.java`
- Create: `src/test/java/dev/ztros/ansac/physics/mlp/MLPFeatureExtractorTest.java`

- [ ] **Step 1: 在 build.gradle 添加测试依赖**

在 `dependencies { }` 末尾添加：

```gradle
    // Testing
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testImplementation 'org.mockito:mockito-core:5.7.0'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.7.0'
```

在文件末尾添加：

```gradle
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: 编写 MovementMLPTest.java**

```java
package dev.ztros.ansac.physics.mlp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementMLPTest {

    @Test
    void testInitializationProducesBoundedOutput() {
        MovementMLP mlp = new MovementMLP(24, 16, 8, 0.01);
        double out = mlp.forward(new double[24]);
        assertTrue(out > 0.0 && out < 1.0, "Initial output should be bounded in (0,1)");
    }

    @Test
    void testOutputAlwaysInUnitInterval() {
        MovementMLP mlp = new MovementMLP(24, 16, 8, 0.01);
        for (int r = 0; r < 20; r++) {
            double[] input = new double[24];
            for (int i = 0; i < 24; i++) {
                input[i] = Math.random() * 2.0 - 0.5;
            }
            double out = mlp.forward(input);
            assertTrue(out >= 0.0 && out <= 1.0,
                "Output out of bounds: " + out);
        }
    }

    @Test
    void testTrainingReducesLoss() {
        MovementMLP mlp = new MovementMLP(4, 4, 2, 0.15);
        double[] input = {0.2, 0.4, 0.6, 0.8};
        double target = 0.85;

        double firstLoss = mlp.train(input, target);
        for (int i = 0; i < 800; i++) {
            mlp.train(input, target);
        }
        double lastLoss = mlp.train(input, target);

        assertTrue(lastLoss < firstLoss,
            "Loss should decrease over training. First=" + firstLoss + ", Last=" + lastLoss);
        double finalOut = mlp.forward(input);
        assertEquals(target, finalOut, 0.03,
            "Output should converge to target");
    }

    @Test
    void testPersistenceRoundTrip() throws Exception {
        java.io.File temp = java.io.File.createTempFile("mlp", ".bin");
        temp.deleteOnExit();

        MovementMLP original = new MovementMLP(24, 16, 8, 0.01);
        double[] input = new double[24];
        for (int i = 0; i < 24; i++) input[i] = 0.33;
        double expected = original.forward(input);

        MLPPersistence.save(original, temp);
        MovementMLP loaded = MLPPersistence.load(temp);
        double actual = loaded.forward(input);

        assertEquals(expected, actual, 1e-9,
            "Serialized and loaded MLP must produce identical output");
    }
}
```

- [ ] **Step 3: 编写 MLPFeatureExtractorTest.java**

```java
package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MLPFeatureExtractorTest {

    @Test
    void testFeatureCountConstant() {
        assertEquals(24, MLPFeatureExtractor.FEATURE_COUNT);
    }

    @Test
    void testExtractReturnsCorrectLength() {
        PlayerPhysicsState state = new PlayerPhysicsState();
        double[] features = MLPFeatureExtractor.extract(state);
        assertEquals(MLPFeatureExtractor.FEATURE_COUNT, features.length);
    }

    @Test
    void testExtractValuesAreNormalized() {
        PlayerPhysicsState state = new PlayerPhysicsState();
        double[] features = MLPFeatureExtractor.extract(state);
        for (int i = 0; i < features.length; i++) {
            assertTrue(features[i] >= -1.0 && features[i] <= 1.0,
                "Feature " + i + " out of normalized range: " + features[i]);
        }
    }

    @Test
    void testExtractWithNullState() {
        double[] features = MLPFeatureExtractor.extract(null);
        assertNotNull(features);
        assertEquals(MLPFeatureExtractor.FEATURE_COUNT, features.length);
        for (double v : features) {
            assertEquals(0.0, v);
        }
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./gradlew test`
Expected: 8 tests passed

---

### Task 14: 全项目编译与打包验证

- [ ] **Step 1: 编译打包**

Run: `./gradlew shadowJar`
Expected: BUILD SUCCESSFUL，输出 `ANSAC-AntiCheat-1.1.0-SNAPSHOT.jar`

- [ ] **Step 2: 确认 plugin.yml 中无合并错误**

Run: `jar tf build/libs/ANSAC-AntiCheat-1.1.0-SNAPSHOT.jar | grep plugin.yml`
Expected: 仅一行 `plugin.yml`

---

## Assumptions & Decisions

1. **Bukkit API 可用性**：`World#getEntityById(int)` 在 Paper/Folia 1.21.4 中可用（自 1.12+ 存在）。
2. **线程安全**：`PlayerPhysicsState` 的读写由 Folia EntityScheduler 保证串行，MLP 的 `forward` 和 `train` 是纯计算无共享状态，可直接调用。
3. **训练数据分布**：采样仅收集受信任玩家数据，标签恒为 `1.0`（正常）。如需异常检测，后续可引入对抗样本（如模拟 Speed/Fly 作弊数据）进行二分类训练。
4. **MiniMessage 版本**：服务器核心已支持 Adventure MiniMessage（Paper/Folia 1.21+ 原生支持）。
5. **Java 版本**：运行时为 Java 21，支持 `var`、`switch` 表达式等新特性。
6. **MLP 结构决策**：输入24维、隐藏层1为16神经元、隐藏层2为8神经元。这是为了在检测精度与计算开销之间取得平衡，确保每 tick 的推理不会显著影响 TPS。
7. **采样目标决策**：默认 5000 个样本。这是基于经验，在 20 tick/s 的服务器上，一个受信任玩家正常移动约 4 分钟即可提供足够数据。

---

## Verification Steps Summary

| 步骤 | 命令 | 预期结果 |
|------|------|----------|
| 编译 | `./gradlew compileJava` | BUILD SUCCESSFUL |
| 单元测试 | `./gradlew test` | 8 tests passed |
| 打包 | `./gradlew shadowJar` | JAR 生成成功 |
| 游戏内采样 | `/ansac sampling start` | 绿色提示「已开始收集」 |
| 游戏内通知 | 受信任玩家移动达标 | admin 收到 MiniMessage 富文本提示 |
| 游戏内训练 | `/ansac sampling continue` | 绿色提示 + 后台训练日志 |
| 游戏内推理 | `/ansac inference <玩家>` | 新增「正常度评分」行 |
| 速度药水修复 | 给玩家速度 II 药水 | `expectedMaxSpeed` 为 `0.2806 * 1.4` |
| 蓝冰修复 | 站在 BLUE_ICE 上 | `onBlueIce = true`，速度含 1.6x |
