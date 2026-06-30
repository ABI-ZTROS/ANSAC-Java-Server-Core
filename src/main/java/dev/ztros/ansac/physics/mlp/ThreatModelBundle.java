package dev.ztros.ansac.physics.mlp;

/**
 * 威胁模型束（B模型）。
 * <p>
 * 由确认的作弊玩家数据训练而来，学习"作弊行为"的概率分布。
 * 与 {@link MovementMLP} / {@link CombatMLP} / {@link CausalFusion}（A模型）
 * 结构相同，但训练标签相反：
 * <ul>
 *   <li>A模型：target=1.0 表示正常行为</li>
 *   <li>B模型：target=1.0 表示作弊行为</li>
 * </ul>
 * </p>
 * <p>
 * 两个模型可以同时进行学习和使用，通过 {@link ModelSelector} 智能决策
 * 哪个模型的结果应该用于定罪。
 * </p>
 *
 * @author ANSAC Physics Engine
 * @see ModelSelector
 */
public final class ThreatModelBundle {

    private final MovementMLP threatMovementMLP;
    private final CombatMLP threatCombatMLP;
    private final CausalFusion threatCausalFusion;

    /**
     * 创建威胁模型束。
     *
     * @param moveInputSize   MovementMLP 输入维度
     * @param moveH1          MovementMLP 第一隐藏层大小
     * @param moveH2          MovementMLP 第二隐藏层大小
     * @param moveLR          MovementMLP 学习率
     * @param combatInputSize CombatMLP 输入维度
     * @param combatH1        CombatMLP 第一隐藏层大小
     * @param combatH2        CombatMLP 第二隐藏层大小
     * @param combatLR        CombatMLP 学习率
     * @param fusionH         CausalFusion 隐藏层大小
     * @param fusionLR        CausalFusion 学习率
     */
    public ThreatModelBundle(int moveInputSize, int moveH1, int moveH2, double moveLR,
                              int combatInputSize, int combatH1, int combatH2, double combatLR,
                              int fusionH, double fusionLR) {
        this.threatMovementMLP = new MovementMLP(moveInputSize, moveH1, moveH2, moveLR);
        this.threatCombatMLP = new CombatMLP(combatInputSize, combatH1, combatH2, combatLR);
        this.threatCausalFusion = new CausalFusion(fusionH, fusionLR);
    }

    /**
     * 使用已有模型实例构建（从持久化文件加载）。
     */
    public ThreatModelBundle(MovementMLP threatMovementMLP, CombatMLP threatCombatMLP,
                             CausalFusion threatCausalFusion) {
        this.threatMovementMLP = threatMovementMLP;
        this.threatCombatMLP = threatCombatMLP;
        this.threatCausalFusion = threatCausalFusion;
    }

    // ==================== 前向推理 ====================

    /**
     * 威胁移动模型前向推理。
     *
     * @param features 84维行为特征向量
     * @return 威胁匹配分数 (0~1, 越高越匹配作弊行为模式)
     */
    public double forwardMovement(double[] features) {
        return threatMovementMLP.forward(features);
    }

    /**
     * 威胁战斗模型前向推理。
     *
     * @param combatFeatures 14维战斗特征向量
     * @return 威胁匹配分数 (0~1, 越高越匹配作弊行为模式)
     */
    public double forwardCombat(double[] combatFeatures) {
        return threatCombatMLP.forward(combatFeatures);
    }

    /**
     * 威胁融合模型前向推理。
     *
     * @param causalInputs 8维因果输入
     * @return 威胁异常分数 (0~1, 越高越确认作弊)
     */
    public double forwardFusion(double[] causalInputs) {
        return threatCausalFusion.forward(causalInputs);
    }

    // ==================== 训练 ====================

    /**
     * 用作弊玩家数据训练（target=1.0）。
     * <p>
     * 用于将确认的作弊行为模式喂入威胁模型，使其学习作弊特征分布。
     * </p>
     *
     * @param features 84维完整行为特征
     * @return 训练损失值
     */
    public double trainOnCheater(double[] features) {
        double moveLoss = threatMovementMLP.train(features, 1.0);
        double[] combatSlice = BehaviorFeatureExtractor.extractCombatSlice(features);
        double combatLoss = threatCombatMLP.train(combatSlice, 1.0);

        double moveScore = threatMovementMLP.forward(features);
        double combatScore = threatCombatMLP.forward(combatSlice);
        double[] causalInputs = buildThreatCausalInputs(moveScore, combatScore);
        double fusionLoss = threatCausalFusion.train(causalInputs, 1.0);

        return (moveLoss + combatLoss + fusionLoss) / 3.0;
    }

    /**
     * 用正常玩家数据训练（target=0.0）。
     * <p>
     * 将正常行为作为负样本喂入威胁模型，使其能区分正常与作弊行为。
     * 这使得B模型不会对所有行为都报高分，而是只对真正的作弊模式报高分。
     * </p>
     *
     * @param features 84维完整行为特征
     * @return 训练损失值
     */
    public double trainOnNormal(double[] features) {
        double moveLoss = threatMovementMLP.train(features, 0.0);
        double[] combatSlice = BehaviorFeatureExtractor.extractCombatSlice(features);
        double combatLoss = threatCombatMLP.train(combatSlice, 0.0);

        double moveScore = threatMovementMLP.forward(features);
        double combatScore = threatCombatMLP.forward(combatSlice);
        double[] causalInputs = buildThreatCausalInputs(moveScore, combatScore);
        double fusionLoss = threatCausalFusion.train(causalInputs, 0.0);

        return (moveLoss + combatLoss + fusionLoss) / 3.0;
    }

    /**
     * 构建威胁模型的因果输入向量。
     * <p>
     * 威胁模型的因果输入与正常模型类似，但语义相反：
     * moveScore/combatScore 表示"作弊匹配度"而非"正常度"。
     * </p>
     */
    private double[] buildThreatCausalInputs(double moveScore, double combatScore) {
        // 复用 BehaviorFeatureExtractor 的因果输入结构
        // 但这里的 moveScore/combatScore 是威胁模型的输出
        return new double[]{
            clamp(moveScore, 0.0, 1.0),
            clamp(combatScore, 0.0, 1.0),
            0.0, // ruleScore: 训练时无规则偏离
            0.0, // envExplain: 作弊行为不受环境解释
            0.5, // speedRatio: 中性值
            0.0, // knockback
            0.0, // headJump
            0.0  // impact
        };
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==================== Getter ====================

    public MovementMLP getThreatMovementMLP() { return threatMovementMLP; }
    public CombatMLP getThreatCombatMLP() { return threatCombatMLP; }
    public CausalFusion getThreatCausalFusion() { return threatCausalFusion; }

    /**
     * 威胁模型是否已训练至少一轮。
     */
    public boolean isReady(int threatTrainRound) {
        return threatTrainRound > 0;
    }
}
