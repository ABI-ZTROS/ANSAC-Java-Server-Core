package dev.ztros.ansac.physics;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * 核心物理预测引擎。
 * <p>
 * 纯计算类，不持有任何可变状态。所有方法均为静态，
 * 提供基于 Minecraft 物理模型的位置预测、速度计算和碰撞检测。
 * </p>
 * <p>
 * 所有物理计算参数来源于 {@link PhysicsConstants}。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public final class PhysicsEngine {

    private PhysicsEngine() {
        throw new UnsupportedOperationException("PhysicsEngine 不可实例化");
    }

    // ==================== 垂直速度预测 ====================

    /**
     * 预测下一个 tick 的垂直速度。
     * <p>
     * 应用 Minecraft 的标准重力模型:
     * <pre>
     * nextVy = GRAVITY_DRAG * (currentVy - GRAVITY_ACCELERATION)
     * </pre>
     * </p>
     *
     * @param currentVy 当前垂直速度（方块/tick）
     * @return 预测的下一 tick 垂直速度（方块/tick）
     */
    public static double predictNextVerticalVelocity(double currentVy) {
        return PhysicsConstants.GRAVITY_DRAG * (currentVy - PhysicsConstants.GRAVITY_ACCELERATION);
    }

    // ==================== 位置预测 ====================

    /**
     * 预测下一个 tick 的位置。
     * <p>
     * 根据当前物理状态和输入速度，计算下一 tick 的位置 [x, y, z]。
     * 综合考虑以下因素:
     * <ul>
     *   <li>重力对垂直速度的影响</li>
     *   <li>飘浮（Levitation）效果对垂直速度的向上修正</li>
     *   <li>缓降（Slow Falling）对重力加速度的削减</li>
     *   <li>终端速度限制</li>
     * </ul>
     * </p>
     *
     * @param state      玩家物理状态
     * @param inputSpeed 水平输入速度（方块/tick）
     * @return 预测的下一 tick 位置 [x, y, z]
     */
    public static double[] predictNextPosition(PlayerPhysicsState state, double inputSpeed) {
        if (state.getCurrentLocation() == null) {
            return new double[]{0.0, 0.0, 0.0};
        }

        double x = state.getCurrentLocation().getX();
        double y = state.getCurrentLocation().getY();
        double z = state.getCurrentLocation().getZ();

        double vy = state.getVelocityY();

        // 计算有效重力加速度
        double gravityAccel = PhysicsConstants.GRAVITY_ACCELERATION;

        // 缓降效果: 重力加速度乘以 0.125
        if (state.hasSlowFalling()) {
            gravityAccel *= PhysicsConstants.SLOW_FALLING_MULTIPLIER;
        }

        // 飘浮效果: 向上力覆盖重力
        if (state.hasLevitation()) {
            int level = state.getLevitationLevel();
            double levitationForce = PhysicsConstants.LEVITATION_SPEED_PER_LEVEL * (level + 1);
            // 飘浮使玩家以固定速度上升，相当于重力被抵消并反转
            vy = levitationForce - gravityAccel;
            vy = PhysicsConstants.GRAVITY_DRAG * vy;
        } else {
            // 正常重力
            vy = predictNextVerticalVelocity(vy);

            // 结降效果: 额外限制下降速度
            if (state.hasSlowFalling() && vy < 0) {
                // 缓降时最大下降速度约为正常终端速度的 1/8
                double slowTerminalVelocity = PhysicsConstants.TERMINAL_VELOCITY * PhysicsConstants.SLOW_FALLING_MULTIPLIER;
                if (vy < -slowTerminalVelocity) {
                    vy = -slowTerminalVelocity;
                }
            }
        }

        // 终端速度限制
        if (vy < -PhysicsConstants.TERMINAL_VELOCITY) {
            vy = -PhysicsConstants.TERMINAL_VELOCITY;
        }

        // 水平位置更新（使用当前水平方向 + 输入速度）
        double vx = state.getVelocityX();
        double vz = state.getVelocityZ();

        // 如果有输入速度，使用输入速度方向
        if (inputSpeed > 0.0 && state.getCurrentLocation() != null) {
            float yaw = state.getCurrentLocation().getYaw();
            double radians = Math.toRadians(yaw);
            vx = -Math.sin(radians) * inputSpeed;
            vz = Math.cos(radians) * inputSpeed;
        }

        // 简单的位置更新
        double nextX = x + vx;
        double nextY = y + vy;
        double nextZ = z + vz;

        return new double[]{nextX, nextY, nextZ};
    }

    // ==================== 水平速度计算 ====================

    /**
     * 计算玩家在当前状态下的预期最大水平速度。
     * <p>
     * 综合考虑以下因素:
     * <ul>
     *   <li>基础行走/疾跑速度</li>
     *   <li>速度药水加成</li>
     *   <li>冰面/蓝冰速度加成</li>
     *   <li>灵魂疾行加成</li>
     *   <li>海豚的恩惠加成</li>
     *   <li>潜行减速</li>
     *   <li>格挡减速</li>
     * </ul>
     * </p>
     *
     * @param state 玩家物理状态
     * @return 预期最大水平速度（方块/tick）
     */
    public static double computeExpectedMaxHorizontalSpeed(PlayerPhysicsState state) {
        // 基础速度
        double baseSpeed = state.isSprinting()
                ? PhysicsConstants.BASE_SPRINT_SPEED
                : PhysicsConstants.BASE_WALK_SPEED;

        // 速度药水加成
        if (state.getSpeedPotionLevel() > 0) {
            baseSpeed *= (1.0 + state.getSpeedPotionLevel() * PhysicsConstants.SPEED_POTION_PER_LEVEL);
        }

        // 潜行减速
        if (state.isSneaking()) {
            baseSpeed *= PhysicsConstants.SNEAK_SPEED_MULTIPLIER;
        }

        // 格挡减速
        if (state.isBlocking()) {
            baseSpeed *= PhysicsConstants.USE_ITEM_SPEED_MULTIPLIER;
        }

        // 水中游泳减速
        if (state.isInWater() && !state.isSprinting()) {
            baseSpeed *= PhysicsConstants.WATER_SWIM_MULTIPLIER;
        }

        // 蜘蛛网：速度降低到 5%
        if (state.isInCobweb()) {
            baseSpeed *= PhysicsConstants.COBWEB_SPEED_MULTIPLIER;
        }

        // 粉雪：速度降低到 ~50%
        if (state.isInPowderSnow()) {
            baseSpeed *= 0.5;
        }

        // 灵魂沙/灵魂土：速度降低到 ~70%
        if (state.isOnSoulSand()) {
            baseSpeed *= 0.7;
        }

        // 粘液块：不直接改变地面速度，但着陆弹跳会产生垂直方向速度

        // 蜂蜜块：不直接改变地面速度，但减速着陆

        // 环境速度乘数（冰面 / 蓝冰）
        if (state.isOnBlueIce()) {
            baseSpeed *= PhysicsConstants.BLUE_ICE_SPEED_MULTIPLIER;
        } else if (state.isOnIce()) {
            baseSpeed *= PhysicsConstants.ICE_SPEED_MULTIPLIER;
        }

        // 海豚的恩惠
        if (state.hasDolphinsGrace()) {
            baseSpeed *= PhysicsConstants.DOLPHINS_GRACE_MULTIPLIER;
        }

        // 灵魂疾行
        if (state.hasSoulSpeed()) {
            double soulSpeedMultiplier = PhysicsConstants.SOUL_SPEED_BASE_MULTIPLIER
                    + state.getSoulSpeedLevel() * PhysicsConstants.SOUL_SPEED_PER_LEVEL;
            baseSpeed *= soulSpeedMultiplier;
        }

        // 泡泡柱：水下提供向上推力，水平加速
        if (state.isAboveBubbleColumn()) {
            baseSpeed *= 1.2;
        }

        // 击退附加速度：击退后短时间内允许更高速度
        if (state.getKnockbackMagnitude() > PhysicsConstants.MIN_KNOCKBACK_SPEED) {
            baseSpeed += state.getKnockbackMagnitude();
        }

        // 顶格跳加速：头顶有方块时跳跃有额外前冲速度
        // 在 Minecraft 中，头顶有方块不会改变最大速度，但实际位移因跳跃高度受限而略短
        // 此处不直接加成 baseSpeed，而是作为特征输入 MLP

        return baseSpeed;
    }

    // ==================== 鞘翅速度预测 ====================

    /**
     * 计算鞘翅飞行的预期速度。
     * <p>
     * 使用线性分段模型:
     * <ul>
     *   <li>俯仰角在 [-90, -50) 时（向下俯冲），速度在 1.0 到 {@link PhysicsConstants#ELYTRA_MAX_DIVE_SPEED} 之间线性增长</li>
     *   <li>俯仰角在 [-50, 0) 时（水平飞行），速度在 {@link PhysicsConstants#ELYTRA_MAX_LEVEL_SPEED} 左右</li>
     *   <li>俯仰角在 [0, 90) 时（向上爬升），速度在 {@link PhysicsConstants#ELYTRA_MIN_CLIMB_SPEED} 到 {@link PhysicsConstants#ELYTRA_MAX_LEVEL_SPEED} 之间线性递减</li>
     * </ul>
     * </p>
     *
     * @param pitch 玩家俯仰角（度数，-90 = 正下方，90 = 正上方）
     * @return 鞘翅预期水平速度（方块/tick）
     */
    public static double computeExpectedElytraSpeed(double pitch) {
        // 标准化 pitch 到 [-90, 90]
        pitch = Math.max(-90.0, Math.min(90.0, pitch));

        if (pitch < -50.0) {
            // 向下俯冲: 线性增长到最大俯冲速度
            // pitch 在 [-90, -50] 映射到速度 [ELYTRA_MAX_DIVE_SPEED, ELYTRA_MAX_LEVEL_SPEED]
            double ratio = (-pitch - 50.0) / 40.0; // 0 at -50, 1 at -90
            return PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED
                    + ratio * (PhysicsConstants.ELYTRA_MAX_DIVE_SPEED - PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED);
        } else if (pitch < 0.0) {
            // 水平偏下飞行
            return PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED;
        } else if (pitch < 50.0) {
            // 向上爬升: 线性递减到最小爬升速度
            // pitch 在 [0, 50] 映射到速度 [ELYTRA_MAX_LEVEL_SPEED, ELYTRA_MIN_CLIMB_SPEED]
            double ratio = pitch / 50.0; // 0 at 0, 1 at 50
            return PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED
                    - ratio * (PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED - PhysicsConstants.ELYTRA_MIN_CLIMB_SPEED);
        } else {
            // 大角度爬升: 最小速度
            return PhysicsConstants.ELYTRA_MIN_CLIMB_SPEED;
        }
    }

    // ==================== 地面验证 ====================

    /**
     * 验证指定位置是否存在可站立地面。
     * <p>
     * 检查位置下方 0.5 格的方块是否存在有效的碰撞箱（BoundingBox），
     * 且碰撞箱顶部高于玩家脚底位置。
     * </p>
     *
     * @param location 待检测位置
     * @return 如果位置下方存在可站立地面则返回 true
     */
    public static boolean verifyGroundState(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        // 检测脚下 0.5 格（考虑浮点误差）
        Block blockBelow = location.clone().subtract(0, 0.5, 0).getBlock();
        double blockTop = getBlockCollisionTop(blockBelow);

        // 如果碰撞箱顶部高于玩家脚底位置减去容差，则认为有地面
        return blockTop >= location.getY() - 0.5;
    }

    // ==================== 下落时间预测 ====================

    /**
     * 模拟计算从指定高度下落所需的 tick 数。
     * <p>
     * 从静止状态开始自由落体，逐 tick 应用重力模型，
     * 直到累计下落距离超过指定高度。
     * </p>
     *
     * @param height 下落高度（方块）
     * @return 下落所需 tick 数
     */
    public static int predictFallTicks(double height) {
        if (height <= 0) {
            return 0;
        }

        int ticks = 0;
        double vy = 0.0;
        double fallen = 0.0;

        while (fallen < height) {
            vy = PhysicsConstants.GRAVITY_DRAG * (vy - PhysicsConstants.GRAVITY_ACCELERATION);

            // 终端速度限制
            if (vy < -PhysicsConstants.TERMINAL_VELOCITY) {
                vy = -PhysicsConstants.TERMINAL_VELOCITY;
            }

            fallen += Math.abs(vy);
            ticks++;

            // 安全限制
            if (ticks > 1000) {
                break;
            }
        }

        return ticks;
    }

    // ==================== 碰撞高度获取 ====================

    /**
     * 获取方块的碰撞箱顶部 Y 坐标。
     * <p>
     * 使用 {@link Block#getBoundingBox()} 获取方块碰撞箱，
     * 返回碰撞箱的顶部 Y 坐标（世界坐标）。
     * </p>
     *
     * @param block 待检测方块
     * @return 碰撞箱顶部 Y 坐标（世界坐标），如果无碰撞箱则返回方块底面 Y 坐标
     */
    public static double getBlockCollisionTop(Block block) {
        if (block == null) {
            return 0.0;
        }

        try {
            BoundingBox bb = block.getBoundingBox();
            return bb.getMaxY();
        } catch (Exception e) {
            // 方块可能没有碰撞箱（如空气、花等），返回方块 Y + 1
            return block.getY() + 1.0;
        }
    }
}
