package dev.ztros.ansac.physics.mlp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗行为异常检测 MLP。
 * 输入层 14 维（CPS、攻击间隔、暴击率、reach、瞄准平滑度、连击数等），
 * 输出层 1 维（0~1，越接近 0 越异常）。
 * <p>
 * 与 MovementMLP 独立运行，专门学习"正常玩家战斗行为"的概率分布。
 * </p>
 */
public final class CombatMLP {

    private final int inputSize;
    private final int hidden1Size;
    private final int hidden2Size;

    private final double[][] W1, W2, W3;
    private final double[] b1, b2, b3;
    private final double learningRate;

    public CombatMLP(int inputSize, int hidden1Size, int hidden2Size, double learningRate) {
        this.inputSize = inputSize;
        this.hidden1Size = hidden1Size;
        this.hidden2Size = hidden2Size;
        this.learningRate = learningRate;

        this.W1 = new double[hidden1Size][inputSize];
        this.b1 = new double[hidden1Size];
        this.W2 = new double[hidden2Size][hidden1Size];
        this.b2 = new double[hidden2Size];
        this.W3 = new double[1][hidden2Size]; // 输出层权重
        this.b3 = new double[1];

        xavierInit(W1, b1, inputSize, hidden1Size);
        xavierInit(W2, b2, hidden1Size, hidden2Size);
        xavierInit(W3, b3, hidden2Size, 1);
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

    public double forward(double[] input) {
        // 隐藏层1: ReLU
        double[] h1 = new double[hidden1Size];
        for (int i = 0; i < hidden1Size; i++) {
            double sum = b1[i];
            for (int j = 0; j < inputSize; j++) {
                sum += W1[i][j] * input[j];
            }
            h1[i] = Math.max(0.0, sum);
        }

        // 隐藏层2: ReLU
        double[] h2 = new double[hidden2Size];
        for (int i = 0; i < hidden2Size; i++) {
            double sum = b2[i];
            for (int j = 0; j < hidden1Size; j++) {
                sum += W2[i][j] * h1[j];
            }
            h2[i] = Math.max(0.0, sum);
        }

        // 输出: Sigmoid
        double sum = b3[0];
        for (int j = 0; j < hidden2Size; j++) {
            sum += W3[0][j] * h2[j];
        }
        return sigmoid(sum);
    }

    /**
     * 训练一步（SGD + 反向传播）。
     * target = 1.0 表示正常行为样本。
     */
    public double train(double[] input, double target) {
        // 前向
        double[] h1 = new double[hidden1Size];
        double[] h1Pre = new double[hidden1Size];
        for (int i = 0; i < hidden1Size; i++) {
            double sum = b1[i];
            for (int j = 0; j < inputSize; j++) sum += W1[i][j] * input[j];
            h1Pre[i] = sum;
            h1[i] = Math.max(0.0, sum);
        }

        double[] h2 = new double[hidden2Size];
        double[] h2Pre = new double[hidden2Size];
        for (int i = 0; i < hidden2Size; i++) {
            double sum = b2[i];
            for (int j = 0; j < hidden1Size; j++) sum += W2[i][j] * h1[j];
            h2Pre[i] = sum;
            h2[i] = Math.max(0.0, sum);
        }

        double outSum = b3[0];
        for (int j = 0; j < hidden2Size; j++) outSum += h2[j];
        double output = sigmoid(outSum);

        // 反向
        double error = output - target;
        double deltaOut = error * output * (1.0 - output);

        double[] deltaH2 = new double[hidden2Size];
        for (int i = 0; i < hidden2Size; i++) {
            deltaH2[i] = deltaOut * (h2Pre[i] > 0 ? 1.0 : 0.0);
        }

        double[] deltaH1 = new double[hidden1Size];
        for (int i = 0; i < hidden1Size; i++) {
            double sum = 0.0;
            for (int j = 0; j < hidden2Size; j++) {
                sum += W2[j][i] * deltaH2[j];
            }
            deltaH1[i] = sum * (h1Pre[i] > 0 ? 1.0 : 0.0);
        }

        // 更新 W3, b3
        for (int j = 0; j < hidden2Size; j++) {
            W3[0][j] -= learningRate * deltaOut * h2[j];
        }
        b3[0] -= learningRate * deltaOut;

        // 更新 W2, b2
        for (int i = 0; i < hidden2Size; i++) {
            for (int j = 0; j < hidden1Size; j++) {
                W2[i][j] -= learningRate * deltaH2[i] * h1[j];
            }
            b2[i] -= learningRate * deltaH2[i];
        }

        // 更新 W1, b1
        for (int i = 0; i < hidden1Size; i++) {
            for (int j = 0; j < inputSize; j++) {
                W1[i][j] -= learningRate * deltaH1[i] * input[j];
            }
            b1[i] -= learningRate * deltaH1[i];
        }
        return error * error; // MSE loss
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

    public int getInputSize()  { return inputSize; }
    public int getHidden1Size() { return hidden1Size; }
    public int getHidden2Size() { return hidden2Size; }
}
