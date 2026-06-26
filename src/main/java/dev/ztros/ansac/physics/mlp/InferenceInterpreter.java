package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;

/**
 * 将 MLP 推理的原始数值翻译成人类可读的自然语言状态描述。
 */
public final class InferenceInterpreter {

    private InferenceInterpreter() {}

    /**
     * 生成一行可读的推理状态摘要，用于 ActionBar 实时显示。
     * 格式：<dark_aqua>AI正在思考: ████████░░ 移动正常 | 战斗待定 | 融合:轻微异常</dark_aqua>
     */
    public static String buildThoughtLine(
            double movementScore, double combatScore, double anomalyScore,
            int trainedRounds) {
        // 移动状态
        String moveTag = tagLabel(movementScore, "移动");
        // 战斗状态
        String combatTag = tagLabel(combatScore, "战斗");
        // 融合状态
        String fusionLabel = fusionTag(anomalyScore);
        // 训练状态
        String trainTag = trainedRounds > 0 ? " 训练" + trainedRounds + "轮" : " 未训练";

        return moveTag + " | " + combatTag + " | " + fusionLabel + trainTag;
    }

    /**
     * 生成多行详细推理报告，用于 /ansac inference 聊天输出。
     */
    public static String buildDetailedThought(double movementScore, double combatScore,
                                              double anomalyScore, PlayerPhysicsState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("<dark_aqua>━━━ AI 思维链 ━━━</dark_aqua>\n");

        // 移动推理
        sb.append("<yellow>移动网络:</yellow> ").append(detailMovement(state, movementScore)).append("\n");

        // 战斗推理
        sb.append("<yellow>战斗网络:</yellow> ").append(detailCombat(combatScore)).append("\n");

        // 融合结论
        sb.append("<yellow>融合决策:</yellow> ").append(detailFusion(anomalyScore)).append("\n");

        // 思维可视化条
        sb.append("<yellow>置信度:</yellow> ").append(buildConfidenceBar(anomalyScore));

        return sb.toString();
    }

    // ==================== 标签生成 ====================

    private static String tagLabel(double score, String prefix) {
        if (score >= 0.7) return "<green>" + prefix + "正常</green>";
        if (score >= 0.5) return "<yellow>" + prefix + "待定</yellow>";
        if (score >= 0.3) return "<red>" + prefix + "可疑</red>";
        return "<dark_red>" + prefix + "异常</dark_red>";
    }

    private static String fusionTag(double anomalyScore) {
        if (anomalyScore >= 0.85) return "<dark_red>融合:严重异常</dark_red>";
        if (anomalyScore >= 0.65) return "<red>融合:高度可疑</red>";
        if (anomalyScore >= 0.45) return "<yellow>融合:可疑</yellow>";
        if (anomalyScore >= 0.25) return "<gray>融合:轻微异常</gray>";
        return "<green>融合:正常</green>";
    }

    // ==================== 详细推理描述 ====================

    private static String detailMovement(PlayerPhysicsState state, double score) {
        double hSpeed = Math.sqrt(
            state.getVelocityX() * state.getVelocityX() + state.getVelocityZ() * state.getVelocityZ());

        String speedDesc;
        if (hSpeed < 0.15) speedDesc = "玩家几乎静止";
        else if (hSpeed < 0.3) speedDesc = "正常步行速度";
        else if (hSpeed < 0.5) speedDesc = "疾跑或受buff影响";
        else speedDesc = "速度偏高(" + String.format("%.2f", hSpeed) + "b/t)";

        String jumpDesc = switch (state.getJumpPhase()) {
            case NONE -> "未跳跃";
            case ASCENDING -> "正在起跳上升";
            case APEX -> "到达跳跃最高点";
            case DESCENDING -> "正在下落";
            case LANDED -> "刚刚落地";
        };

        String groundDesc = state.isClientOnGround() ? "地面" : "空中";

        String conclusion = score >= 0.6 ? "→ 移动行为正常"
            : score >= 0.4 ? "→ 移动略有偏差，继续观察"
            : "→ 移动异常，可能是速度作弊/飞行";

        return speedDesc + ", " + jumpDesc + ", " + groundDesc + " " + conclusion;
    }

    private static String detailCombat(double score) {
        String conclusion = score >= 0.6 ? "战斗模式正常"
            : score >= 0.4 ? "战斗数据不足/轻微异常"
            : "战斗严重异常，可能是自动点击器/杀戮光环";
        return conclusion + " (正常度:" + String.format("%.0f%%", score * 100) + ")";
    }

    private static String detailFusion(double anomalyScore) {
        String level = anomalyScore >= 0.85 ? "严重异常 - 高概率作弊"
            : anomalyScore >= 0.65 ? "高度可疑 - 需要关注"
            : anomalyScore >= 0.45 ? "轻度可疑 - 可能是高手或误判"
            : anomalyScore >= 0.25 ? "轻微偏差 - 正常波动范围内"
            : "正常 - 行为符合预期";
        return "异常度 " + String.format("%.1f%%", anomalyScore * 100) + " → " + level;
    }

    // ==================== 可视化 ====================

    /**
     * 构建置信度可视化条（用于聊天）。
     * 从左到右：绿色(正常) → 黄色(待定) → 红色(异常)
     */
    public static String buildConfidenceBar(double anomalyScore) {
        int length = 20;
        int filled = (int) Math.round(anomalyScore * length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                double ratio = (double) i / length;
                if (ratio < 0.3) sb.append("<green>■</green>");
                else if (ratio < 0.6) sb.append("<yellow>■</yellow>");
                else sb.append("<red>■</red>");
            } else {
                sb.append("<dark_gray>░</dark_gray>");
            }
        }
        sb.append(" <white>").append(String.format("%.1f%%", anomalyScore * 100)).append("</white>");
        return sb.toString();
    }

    /**
     * 构建简洁的思维状态图标条（用于 ActionBar）。
     * ●=活跃思考 ○=静默
     */
    public static String buildThinkingIndicator(double anomalyScore) {
        int active = (int) Math.round(anomalyScore * 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < active ? "●" : "○");
        }
        return sb.toString();
    }
}
