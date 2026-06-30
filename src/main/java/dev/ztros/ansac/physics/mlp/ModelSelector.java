package dev.ztros.ansac.physics.mlp;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能模型选择算法。
 * <p>
 * 核心思想：综合 A 模型（正常模型）和 B 模型（威胁模型）的推理结果，
 * 智能决策应该使用哪个模型的证据进行定罪。
 * </p>
 *
 * <p><b>决策逻辑：</b></p>
 * <ul>
 *   <li>A模型异常 + B模型匹配 → {@link VerdictSource#DUAL_CONFIRM} 双重确认，最高置信度</li>
 *   <li>仅 B模型匹配 → {@link VerdictSource#MODEL_B_ONLY} 威胁模式命中</li>
 *   <li>仅 A模型异常 → {@link VerdictSource#MODEL_A_ONLY} 行为异常但无作弊模式匹配</li>
 *   <li>两者均不达标 → {@link VerdictSource#INSUFFICIENT} 证据不足</li>
 * </ul>
 *
 * <p><b>权重动态调整：</b></p>
 * <ul>
 *   <li>高危玩家（已确认作弊者）：B模型权重提升，更信任威胁模式匹配</li>
 *   <li>普通玩家：A模型权重较高，遵循"无罪推定"原则</li>
 *   <li>规则层VL持续累积：逐步提升整体置信度</li>
 * </ul>
 *
 * @author ANSAC Physics Engine
 */
public final class ModelSelector {

    // ==================== 配置参数 ====================

    /** A模型（正常模型）权重 */
    private double modelAWeight;

    /** B模型（威胁模型）权重 */
    private double modelBWeight;

    /** 规则层权重 */
    private double ruleWeight;

    /** 双模型确认阈值（两者都超过此值时为 DUAL_CONFIRM） */
    private double dualConfirmThreshold;

    /** 单模型定罪阈值 */
    private double singleConvictThreshold;

    /** 高危玩家B模型权重加成倍数 */
    private double highRiskBWeightBoost;

    // ==================== 构造函数 ====================

    public ModelSelector(double modelAWeight, double modelBWeight, double ruleWeight,
                         double dualConfirmThreshold, double singleConvictThreshold,
                         double highRiskBWeightBoost) {
        this.modelAWeight = modelAWeight;
        this.modelBWeight = modelBWeight;
        this.ruleWeight = ruleWeight;
        this.dualConfirmThreshold = dualConfirmThreshold;
        this.singleConvictThreshold = singleConvictThreshold;
        this.highRiskBWeightBoost = highRiskBWeightBoost;
    }

    // ==================== 核心评估 ====================

    /**
     * 评估双模型推理结果，决定定罪策略。
     *
     * @param normalScore  A模型正常度评分 (0~1, 1=完全正常)
     * @param threatScore  B模型威胁匹配度 (0~1, 1=高度匹配作弊模式)
     * @param ruleFactor   规则层偏离因子 (0~1, 1=规则严重违规)
     * @param isHighRisk   玩家是否被标记为高危（已确认作弊者）
     * @return 模型选择结果，包含置信度和定罪建议
     */
    public ModelSelectorResult evaluate(double normalScore, double threatScore,
                                        double ruleFactor, boolean isHighRisk) {

        // 将A模型正常度转为异常度
        double abnA = 1.0 - clamp(normalScore, 0.0, 1.0);
        double matchB = clamp(threatScore, 0.0, 1.0);
        double rule = clamp(ruleFactor, 0.0, 1.0);

        // 动态权重：高危玩家B模型权重提升
        double effectiveBWeight = modelBWeight;
        if (isHighRisk) {
            effectiveBWeight *= highRiskBWeightBoost;
        }

        // 权重归一化
        double totalWeight = modelAWeight + effectiveBWeight + ruleWeight;
        double wA = modelAWeight / totalWeight;
        double wB = effectiveBWeight / totalWeight;
        double wR = ruleWeight / totalWeight;

        // 加权综合置信度
        double confidence = wA * abnA + wB * matchB + wR * rule;

        // 决策逻辑
        VerdictSource source;
        boolean shouldConvict;
        StringBuilder reasoning = new StringBuilder();
        List<ConvictionFactor> factors = new ArrayList<>();

        boolean aAbnormal = abnA >= dualConfirmThreshold;
        boolean bMatched = matchB >= dualConfirmThreshold;

        if (aAbnormal && bMatched) {
            // 双模型一致：A说异常 + B说作弊 → 最高置信度
            source = VerdictSource.DUAL_CONFIRM;
            shouldConvict = confidence >= singleConvictThreshold;
            reasoning.append("双模型一致确认: A模型异常度=")
                    .append(String.format("%.2f%%", abnA * 100))
                    .append(", B模型威胁匹配=")
                    .append(String.format("%.2f%%", matchB * 100));
            factors.add(new ConvictionFactor("A_MODEL", "行为异常(正常度"
                    + String.format("%.0f%%", normalScore * 100) + ")", wA));
            factors.add(new ConvictionFactor("B_MODEL", "威胁模式匹配("
                    + String.format("%.0f%%", matchB * 100) + ")", wB));
        } else if (bMatched) {
            // 仅B模型命中：威胁模式匹配，但A模型未报异常
            source = VerdictSource.MODEL_B_ONLY;
            shouldConvict = confidence >= singleConvictThreshold;
            reasoning.append("B模型威胁匹配: 威胁度=")
                    .append(String.format("%.2f%%", matchB * 100))
                    .append(", A模型正常度=")
                    .append(String.format("%.2f%%", normalScore * 100));
            factors.add(new ConvictionFactor("B_MODEL", "威胁模式匹配("
                    + String.format("%.0f%%", matchB * 100) + ")", wB));
        } else if (aAbnormal) {
            // 仅A模型异常：行为异常但未匹配已知作弊模式
            source = VerdictSource.MODEL_A_ONLY;
            // 单A模型异常需要更高置信度才定罪
            shouldConvict = confidence >= singleConvictThreshold + 0.1;
            reasoning.append("A模型行为异常: 异常度=")
                    .append(String.format("%.2f%%", abnA * 100))
                    .append(", B模型未匹配已知作弊模式");
            factors.add(new ConvictionFactor("A_MODEL", "行为异常(异常度"
                    + String.format("%.0f%%", abnA * 100) + ")", wA));
        } else {
            // 证据不足
            source = VerdictSource.INSUFFICIENT;
            shouldConvict = false;
            reasoning.append("证据不足: A异常度=")
                    .append(String.format("%.2f%%", abnA * 100))
                    .append(", B匹配=")
                    .append(String.format("%.2f%%", matchB * 100));
        }

        // 高危玩家额外说明
        if (isHighRisk) {
            reasoning.append(" [高危玩家: B模型权重x")
                    .append(String.format("%.1f", highRiskBWeightBoost))
                    .append("]");
        }

        // 规则层 factor（如果有 VL 贡献）
        if (rule > 0.0) {
            factors.add(new ConvictionFactor("RULE_LAYER", "规则层偏离(VL="
                    + String.format("%.0f%%", rule * 100) + ")", wR));
        }

        return new ModelSelectorResult(
            source, confidence, abnA, matchB, rule, shouldConvict, reasoning.toString(), factors
        );
    }

    /**
     * 获取置信度等级标签。
     */
    public static String getConfidenceLabel(double confidence) {
        if (confidence >= 0.85) return "极高";
        if (confidence >= 0.70) return "高";
        if (confidence >= 0.55) return "中";
        if (confidence >= 0.40) return "低";
        return "极低";
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==================== Getter/Setter ====================

    public double getModelAWeight() { return modelAWeight; }
    public void setModelAWeight(double w) { this.modelAWeight = w; }
    public double getModelBWeight() { return modelBWeight; }
    public void setModelBWeight(double w) { this.modelBWeight = w; }
    public double getRuleWeight() { return ruleWeight; }
    public void setRuleWeight(double w) { this.ruleWeight = w; }
    public double getDualConfirmThreshold() { return dualConfirmThreshold; }
    public double getSingleConvictThreshold() { return singleConvictThreshold; }
    public double getHighRiskBWeightBoost() { return highRiskBWeightBoost; }

    // ==================== 结果类型 ====================

    /**
     * 定罪来源枚举。
     */
    public enum VerdictSource {
        /** 双模型一致确认：A异常 + B匹配，最高置信度 */
        DUAL_CONFIRM,
        /** 仅B模型命中：威胁模式匹配 */
        MODEL_B_ONLY,
        /** 仅A模型异常：行为异常但未匹配已知作弊模式 */
        MODEL_A_ONLY,
        /** 证据不足：两个模型均未达标 */
        INSUFFICIENT
    }

    /**
     * 模型选择评估结果。
     *
     * @param source       定罪来源
     * @param confidence   综合置信度 (0~1)
     * @param abnormalityA A模型异常度 (0~1)
     * @param threatMatchB B模型威胁匹配度 (0~1)
     * @param ruleFactor   规则层偏离因子 (0~1)
     * @param shouldConvict 是否建议定罪
     * @param reasoning     人类可读的推理过程
     * @param factors      结构化定罪原因列表（按贡献度降序）
     */
    public record ModelSelectorResult(
        VerdictSource source,
        double confidence,
        double abnormalityA,
        double threatMatchB,
        double ruleFactor,
        boolean shouldConvict,
        String reasoning,
        List<ConvictionFactor> factors
    ) {}

    /**
     * 结构化定罪因子。
     * @param source  来源: A_MODEL, B_MODEL, RULE_LAYER, CAUSAL
     * @param name    具体因素名称（如"移动速度异常"、"击退不匹配"）
     * @param weight  该因素对最终置信度的贡献权重 (0~1)
     */
    public record ConvictionFactor(String source, String name, double weight) {}
}
