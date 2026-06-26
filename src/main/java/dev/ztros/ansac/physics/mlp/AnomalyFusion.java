package dev.ztros.ansac.physics.mlp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 异常融合决策网络。
 * <p>
 * 输入：MovementMLP 输出(1) + CombatMLP 输出(1) + 规则层异常分数(1)
 * 输出：最终异常概率(0~1)，越接近 1 越异常。
 * </p>
 * <p>
 * 用途：将"移动异常"、"战斗异常"、"规则偏离"三个信号融合为一个
 * 统一的判罪分数。支持纯模型接管模式(HYBRID / MODEL_ONLY)。
 * </p>
 */
public final class AnomalyFusion {

    private final double[][] W1;
    private final double[] b1, b2;
    private final double learningRate;

    public AnomalyFusion(double learningRate) {
        this.learningRate = learningRate;
        // 输入3维: movementScore, combatScore, ruleScore
        // 隐藏层4维
        this.W1 = new double[4][3];
        this.b1 = new double[4];
        this.b2 = new double[1];
        xavierInit(W1, b1, 3, 4);
        b2[0] = ThreadLocalRandom.current().nextDouble(-0.1, 0.1);
    }

    private void xavierInit(double[][] w, double[] b, int in, int out) {
        double bound = Math.sqrt(6.0 / (in + out));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < w.length; i++) {
            b[i] = rng.nextDouble(-bound, bound);
            for (int j = 0; j < w[i].length; j++) {
                w[i][j] = rng.nextDouble(-bound, bound);
            }
        }
    }

    /**
     * 前向传播，返回异常概率(0~1)。
     * @param movementScore MovementMLP 正常度(0~1)
     * @param combatScore   CombatMLP 正常度(0~1)
     * @param ruleScore     规则偏离度(0~1, 0=无偏离, 1=严重偏离)
     * @return 异常概率(0~1)，越高越异常
     */
    public double forward(double movementScore, double combatScore, double ruleScore) {
        double[] input = {movementScore, combatScore, ruleScore};

        // 隐藏层: ReLU
        double[] h = new double[4];
        for (int i = 0; i < 4; i++) {
            double sum = b1[i];
            for (int j = 0; j < 3; j++) {
                sum += W1[i][j] * input[j];
            }
            h[i] = Math.max(0.0, sum);
        }

        // 输出: Sigmoid
        double sum = b2[0];
        for (int j = 0; j < 4; j++) {
            sum += h[j];
        }
        return sigmoid(sum);
    }

    /**
     * 训练一步。target=0 表示正常（无异常），target=1 表示异常。
     */
    public void train(double movementScore, double combatScore, double ruleScore, double target) {
        double[] input = {movementScore, combatScore, ruleScore};

        // 前向
        double[] h = new double[4];
        double[] hPre = new double[4];
        for (int i = 0; i < 4; i++) {
            double sum = b1[i];
            for (int j = 0; j < 3; j++) sum += W1[i][j] * input[j];
            hPre[i] = sum;
            h[i] = Math.max(0.0, sum);
        }

        double outSum = b2[0];
        for (int j = 0; j < 4; j++) outSum += h[j];
        double output = sigmoid(outSum);

        // 反向
        double error = output - target;
        double deltaOut = error * output * (1.0 - output);

        double[] deltaH = new double[4];
        for (int i = 0; i < 4; i++) {
            deltaH[i] = deltaOut * (hPre[i] > 0 ? 1.0 : 0.0);
        }

        // 更新
        for (int j = 0; j < 4; j++) {
            b2[0] -= learningRate * deltaOut;
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) {
                W1[i][j] -= learningRate * deltaH[i] * input[j];
            }
            b1[i] -= learningRate * deltaH[i];
        }
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

    /**
     * 根据异常概率给出判罪标签。
     */
    public static String getVerdictLabel(double anomalyScore) {
        if (anomalyScore >= 0.85) return "严重异常";
        if (anomalyScore >= 0.65) return "高度可疑";
        if (anomalyScore >= 0.45) return "可疑";
        if (anomalyScore >= 0.25) return "轻微异常";
        return "正常";
    }
}
