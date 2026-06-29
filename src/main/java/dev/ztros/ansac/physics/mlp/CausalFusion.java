package dev.ztros.ansac.physics.mlp;

import java.io.Serializable;

/**
 * 因果融合决策网络（CausalFusion）。
 * <p>
 * 在 AnomalyFusion 基础上引入因果推理能力：不仅看"异常程度"，还看
 * "异常是否有合理解释"。例如：玩家速度很快，但如果脚下是蓝冰且有
 * 速度药水，则环境解释力高，不应判为作弊。
 * </p>
 * <p>
 * 输入 8 维：
 * <ol>
 *   <li>movementScore - MovementMLP 正常度 (0~1)</li>
 *   <li>combatScore - CombatMLP 正常度 (0~1)</li>
 *   <li>ruleScore - 规则层偏离度 (0~1)</li>
 *   <li>envExplainability - 环境解释力 (0~1, 多少速度可由环境解释)</li>
 *   <li>speedRatio - 实际速度 / 理论最大速度 (归一化到 0~1)</li>
 *   <li>knockbackForce - 击退力度 (归一化到 0~1)</li>
 *   <li>headJumpActive - 顶格跳适用中 (0或1)</li>
 *   <li>impactEventActive - 冲击事件活跃 (0或1)</li>
 * </ol>
 * 输出 1 维：最终异常概率 (0~1)，越高越异常。
 * </p>
 */
public final class CausalFusion implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int INPUT_SIZE = 8;

    private final int hiddenSize;
    private final double[][] W1, W2;
    private final double[] b1, b2;
    private final double learningRate;

    public CausalFusion(int hiddenSize, double learningRate) {
        if (hiddenSize <= 0) throw new IllegalArgumentException("hiddenSize must be positive");
        this.hiddenSize = hiddenSize;
        this.learningRate = learningRate;

        // 输入8维 -> 隐藏层 -> 输出1维
        this.W1 = new double[hiddenSize][INPUT_SIZE];
        this.b1 = new double[hiddenSize];
        this.W2 = new double[1][hiddenSize];
        this.b2 = new double[1];

        MLPActivations.xavierInit(W1, b1, INPUT_SIZE, hiddenSize);
        MLPActivations.xavierInit(W2, b2, hiddenSize, 1);
    }

    /**
     * 前向传播。
     * @param inputs 8维因果输入向量
     * @return 异常概率(0~1)
     */
    public double forward(double[] inputs) {
        if (inputs == null || inputs.length != INPUT_SIZE) {
            throw new IllegalArgumentException("CausalFusion input size mismatch: expected " + INPUT_SIZE);
        }

        // 隐藏层: ReLU
        double[] h = new double[hiddenSize];
        double[] hPre = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double sum = b1[i];
            for (int j = 0; j < INPUT_SIZE; j++) {
                sum += W1[i][j] * inputs[j];
            }
            hPre[i] = sum;
            h[i] = Math.max(0.0, sum);
        }

        // 输出: Sigmoid
        double outSum = b2[0];
        for (int j = 0; j < hiddenSize; j++) {
            outSum += W2[0][j] * h[j];
        }
        return MLPActivations.sigmoid(outSum);
    }

    /**
     * 训练一步（SGD + 反向传播）。
     * @param inputs 8维因果输入
     * @param target 目标值: 0=正常, 1=异常
     */
    public double train(double[] inputs, double target) {
        if (inputs == null || inputs.length != INPUT_SIZE) {
            throw new IllegalArgumentException("Input size mismatch");
        }

        // 前向
        double[] h = new double[hiddenSize];
        double[] hPre = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double sum = b1[i];
            for (int j = 0; j < INPUT_SIZE; j++) sum += W1[i][j] * inputs[j];
            hPre[i] = sum;
            h[i] = Math.max(0.0, sum);
        }

        double outSum = b2[0];
        for (int j = 0; j < hiddenSize; j++) outSum += W2[0][j] * h[j];
        double output = MLPActivations.sigmoid(outSum);

        // 反向
        double error = output - target;
        double deltaOut = error * output * (1.0 - output);

        double[] deltaH = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            deltaH[i] = deltaOut * (hPre[i] > 0 ? 1.0 : 0.0);
        }

        // 更新 W2, b2
        for (int j = 0; j < hiddenSize; j++) {
            W2[0][j] = MLPActivations.clampWeight(W2[0][j] - learningRate * MLPActivations.clipGrad(deltaOut) * h[j]);
        }
        b2[0] -= learningRate * MLPActivations.clipGrad(deltaOut);

        // 更新 W1, b1
        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < INPUT_SIZE; j++) {
                W1[i][j] = MLPActivations.clampWeight(W1[i][j] - learningRate * MLPActivations.clipGrad(deltaH[i]) * inputs[j]);
            }
            b1[i] -= learningRate * MLPActivations.clipGrad(deltaH[i]);
        }

        return error * error; // MSE loss
    }

    public int getHiddenSize() { return hiddenSize; }
    public int getInputSize() { return INPUT_SIZE; }
    public double[][] getW1() { return W1; }
    public double[] getB1() { return b1; }
    public double[][] getW2() { return W2; }
    public double[] getB2() { return b2; }
    public double getLearningRate() { return learningRate; }

    public static String getVerdictLabel(double anomalyScore) {
        if (anomalyScore >= 0.85) return "严重异常";
        if (anomalyScore >= 0.65) return "高度可疑";
        if (anomalyScore >= 0.45) return "可疑";
        if (anomalyScore >= 0.25) return "轻微异常";
        return "正常";
    }
}
