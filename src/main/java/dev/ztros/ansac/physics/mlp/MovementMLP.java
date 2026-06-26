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
        MLPInferenceDetail detail = forwardDetailed(input);
        return detail.getOutputScore();
    }

    /**
     * 带详情的前向传播，返回各层激活值，用于可视化推理过程。
     */
    public MLPInferenceDetail forwardDetailed(double[] input) {
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
        double output = sigmoid(sum);
        return new MLPInferenceDetail(input.clone(), h1, h2, output);
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
            w3[i] = MLPActivations.clampWeight(w3[i] + learningRate * MLPActivations.clipGrad(dOut) * h2Post[i]);
        }
        b3 += learningRate * MLPActivations.clipGrad(dOut);

        for (int i = 0; i < hidden1Size; i++) {
            for (int j = 0; j < hidden2Size; j++) {
                w2[i][j] = MLPActivations.clampWeight(w2[i][j] + learningRate * MLPActivations.clipGrad(dH2[j]) * h1Post[i]);
            }
        }
        for (int j = 0; j < hidden2Size; j++) {
            b2[j] += learningRate * MLPActivations.clipGrad(dH2[j]);
        }

        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hidden1Size; j++) {
                w1[i][j] = MLPActivations.clampWeight(w1[i][j] + learningRate * MLPActivations.clipGrad(dH1[j]) * input[i]);
            }
        }
        for (int j = 0; j < hidden1Size; j++) {
            b1[j] += learningRate * MLPActivations.clipGrad(dH1[j]);
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
