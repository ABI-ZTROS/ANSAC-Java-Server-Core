package dev.ztros.ansac.physics.mlp;

/**
 * 双模型推理结果 -- 不可变快照。
 * <p>
 * 包含 A 模型（正常模型）和 B 模型（威胁模型）的完整推理结果，
 * 以及 {@link ModelSelector} 的综合评估结论。
 * </p>
 *
 * @author ANSAC Physics Engine
 * @see ModelSelector
 * @see ThreatModelBundle
 */
public record DualInferenceResult(
        /** A模型移动正常度 (0~1, 1=完全正常) */
        double normalMovementScore,
        /** A模型战斗正常度 (0~1, 1=完全正常) */
        double normalCombatScore,
        /** A模型因果融合异常度 (0~1, 1=严重异常) */
        double normalAnomalyScore,
        /** B模型移动威胁匹配度 (0~1, 1=高度匹配作弊模式) */
        double threatMovementScore,
        /** B模型战斗威胁匹配度 (0~1, 1=高度匹配作弊模式) */
        double threatCombatScore,
        /** B模型因果融合威胁度 (0~1, 1=确认作弊) */
        double threatFusionScore,
        /** 模型选择器评估结果 */
        ModelSelector.ModelSelectorResult selectorResult,
        /** 玩家是否为高危标记 */
        boolean isHighRisk
) {
    /**
     * 空结果，用于无数据时回退。
     */
    public static final DualInferenceResult EMPTY = new DualInferenceResult(
            0.5, 0.5, 0.0, 0.0, 0.0, 0.0,
            null, false
    );

    /**
     * 快速判断是否建议定罪。
     */
    public boolean shouldConvict() {
        return selectorResult != null && selectorResult.shouldConvict();
    }

    /**
     * 获取综合置信度。
     */
    public double getConfidence() {
        return selectorResult != null ? selectorResult.confidence() : 0.0;
    }

    /**
     * 获取定罪来源。
     */
    public ModelSelector.VerdictSource getVerdictSource() {
        return selectorResult != null ? selectorResult.source() : ModelSelector.VerdictSource.INSUFFICIENT;
    }
}
