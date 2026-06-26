package dev.ztros.ansac.physics;

/**
 * 反作弊检测运行模式。
 */
public enum DetectionMode {
    /** 纯规则模式：传统 if/else 检测，MLP 仅作为观察参考 */
    RULE_ONLY,
    /** 纯模型模式：MLP 异常分数直接决定是否处罚，规则层作为参考 */
    MODEL_ONLY,
    /** 混合双打模式：规则异常分数 + MLP 异常分数通过 AnomalyFusion 融合决策 */
    HYBRID;

    public static DetectionMode fromString(String s) {
        if (s == null) return HYBRID;
        return switch (s.toUpperCase().replace("-", "_")) {
            case "RULE", "RULE_ONLY", "ONLY_RULE" -> RULE_ONLY;
            case "MODEL", "MODEL_ONLY", "ONLY_MODEL", "AI" -> MODEL_ONLY;
            default -> HYBRID;
        };
    }
}
