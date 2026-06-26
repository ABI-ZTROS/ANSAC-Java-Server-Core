package dev.ztros.ansac.physics;

import org.bukkit.Location;

/**
 * 物理推理结果 -- 不可变快照。
 * <p>
 * 每次物理检查时生成，包含玩家当前物理状态的完整快照以及
 * 与预期物理模型计算结果的比较数据。
 * 通过 {@link #isSuspicious(double)} 可以快速判断该状态是否可疑。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public record InferenceResult(
        /** 玩家当前位置快照 */
        Location currentLocation,

        /** X 轴速度（方块/tick） */
        double velocityX,

        /** Y 轴速度（方块/tick） */
        double velocityY,

        /** Z 轴速度（方块/tick） */
        double velocityZ,

        /** 水平合成速度（方块/tick） */
        double horizontalSpeed,

        /** 客户端报告的着地状态 */
        boolean clientOnGround,

        /** 服务端验证的着地状态 */
        boolean serverVerifiedGround,

        /** 是否处于跳跃周期中 */
        boolean inJumpCycle,

        /** 是否在空中（未着地且未在水中） */
        boolean inAir,

        /** 是否拥有飘浮效果 */
        boolean hasLevitation,

        /** 是否拥有缓降效果 */
        boolean hasSlowFalling,

        /** 是否正在滑翔（鞘翅） */
        boolean isGliding,

        /** 是否站在冰面上 */
        boolean onIce,

        /** 是否站在蓝冰上 */
        boolean onBlueIce,

        /** 是否处于潜行状态 */
        boolean isSneaking,

        /** 是否处于疾跑状态 */
        boolean isSprinting,

        /** 当前跳跃阶段 */
        PlayerPhysicsState.JumpPhase jumpPhase,

        /** 起跳时的水平速度 */
        double jumpTakeoffHorizontalSpeed,

        /** 当前下落距离（方块） */
        double fallDistance,

        /** 离开地面后的 tick 数 */
        int ticksSinceLeftGround,

        /** 起跳点的 Y 坐标 */
        double jumpStartY,

        /** 跳跃最高点的 Y 坐标 */
        double jumpPeakY,

        /** 预测的下一 tick 垂直速度 */
        double predictedVelocityY,

        /** 基于当前状态计算的预期最大水平速度 */
        double expectedMaxHorizontalSpeed,

        /** 速度药水等级 */
        int speedPotionLevel,

        /** 跳跃增益等级 */
        int jumpBoostLevel
) {

    /**
     * 空实例，用于不存在推理数据时的回退。
     * <p>所有数值字段为 0，布尔字段为 false，jumpPhase 为 NONE，currentLocation 为 null。</p>
     */
    public static final InferenceResult EMPTY = new InferenceResult(
            null,
            0.0, 0.0, 0.0, 0.0,
            false, false,
            false, false, false, false, false,
            false, false, false, false,
            PlayerPhysicsState.JumpPhase.NONE,
            0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0,
            0, 0
    );

    /**
     * 计算水平速度偏差比。
     * <p>
     * 返回实际水平速度与预期最大水平速度的比值。
     * 当预期最大速度为 0 时返回 0（避免除零）。
     * 比值 &gt; 1.0 表示实际速度超过预期，可能可疑。
     * </p>
     *
     * @return 速度偏差比（实际 / 预期最大），预期为 0 时返回 0
     */
    public double getSpeedDeviationRatio() {
        if (expectedMaxHorizontalSpeed <= 0.0) {
            return 0.0;
        }
        return horizontalSpeed / expectedMaxHorizontalSpeed;
    }

    /**
     * 计算垂直偏差。
     * <p>
     * 返回当前垂直速度与预测垂直速度的绝对差值。
     * </p>
     *
     * @return 垂直速度偏差的绝对值
     */
    public double getVerticalDeviation() {
        return Math.abs(velocityY - predictedVelocityY);
    }

    /**
     * 判断当前物理状态是否可疑。
     * <p>
     * 综合水平速度偏差和垂直偏差进行判断：
     * 如果水平速度偏差比超过 (1 + tolerance) 或垂直偏差超过 tolerance，
     * 则认为该状态可疑。
     * </p>
     *
     * @param tolerance 容差系数（如 0.1 表示允许 10% 的偏差）
     * @return 如果物理状态超过容差范围则返回 true
     */
    public boolean isSuspicious(double tolerance) {
        return getSpeedDeviationRatio() > (1.0 + tolerance)
                || getVerticalDeviation() > tolerance;
    }
}
