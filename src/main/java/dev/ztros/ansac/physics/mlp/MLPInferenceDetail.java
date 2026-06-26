package dev.ztros.ansac.physics.mlp;

/**
 * MLP 推理详情 -- 记录模型一次前向传播的完整思考过程。
 */
public final class MLPInferenceDetail {

    private final double[] inputFeatures;
    private final double[] hidden1Activations;
    private final double[] hidden2Activations;
    private final double outputScore;

    public MLPInferenceDetail(double[] inputFeatures, double[] hidden1Activations,
                              double[] hidden2Activations, double outputScore) {
        this.inputFeatures = inputFeatures;
        this.hidden1Activations = hidden1Activations;
        this.hidden2Activations = hidden2Activations;
        this.outputScore = outputScore;
    }

    public double[] getInputFeatures() { return inputFeatures; }
    public double[] getHidden1Activations() { return hidden1Activations; }
    public double[] getHidden2Activations() { return hidden2Activations; }
    public double getOutputScore() { return outputScore; }

    /**
     * 将输出评分翻译为人类可读的判定标签和颜色。
     */
    public String getVerdictLabel() {
        if (outputScore >= 0.8) return "正常";
        if (outputScore >= 0.6) return "轻微异常";
        if (outputScore >= 0.4) return "可疑";
        if (outputScore >= 0.2) return "高度可疑";
        return "严重异常";
    }
}
