package dev.ztros.ansac.physics.mlp;

/**
 * MLP 网络公共工具：激活函数、初始化、梯度裁剪。
 * 避免三个网络各自重复实现 sigmoid/xavierInit/clipGrad。
 */
public final class MLPActivations {

    /** 梯度裁剪阈值（可通过 setGradClip 动态调整） */
    private static double GRAD_CLIP = 5.0;

    /** 权重裁剪阈值（可通过 setWeightClip 动态调整） */
    private static double WEIGHT_CLIP = 50.0;

    private MLPActivations() {}

    public static double getGradClip() { return GRAD_CLIP; }
    public static double getWeightClip() { return WEIGHT_CLIP; }
    public static void setGradClip(double v) { GRAD_CLIP = v; }
    public static void setWeightClip(double v) { WEIGHT_CLIP = v; }

    /**
     * 数值稳定的 sigmoid。
     * 根据 x 符号选择避免 exp 溢出的分支。
     */
    public static double sigmoid(double x) {
        if (x >= 0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        } else {
            double z = Math.exp(x);
            return z / (1.0 + z);
        }
    }

    /**
     * Xavier 初始化权重矩阵和偏置向量。
     */
    public static void xavierInit(double[][] w, double[] b, int in, int out) {
        double bound = Math.sqrt(6.0 / (in + out));
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < w.length; i++) {
            b[i] = rng.nextDouble(-bound, bound);
            for (int j = 0; j < w[i].length; j++) {
                w[i][j] = rng.nextDouble(-bound, bound);
            }
        }
    }

    /**
     * 梯度裁剪：将梯度值限制在 [-GRAD_CLIP, GRAD_CLIP] 范围内。
     * 防止训练过程中梯度爆炸导致权重发散。
     */
    public static double clipGrad(double grad) {
        if (grad > GRAD_CLIP) return GRAD_CLIP;
        if (grad < -GRAD_CLIP) return -GRAD_CLIP;
        return grad;
    }

    /**
     * 将权重值裁剪到安全范围，防止极端值。
     */
    public static double clampWeight(double w) {
        if (Double.isNaN(w) || Double.isInfinite(w)) return 0.0;
        return Math.max(-WEIGHT_CLIP, Math.min(WEIGHT_CLIP, w));
    }
}
