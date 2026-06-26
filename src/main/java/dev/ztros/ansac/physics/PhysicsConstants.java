package dev.ztros.ansac.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一物理常量库。
 * <p>
 * 所有常量均来源于 Minecraft Wiki，用于物理引擎的预测和验证。
 * 此类不可实例化，所有字段均为静态常量。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public final class PhysicsConstants {

    private PhysicsConstants() {
        throw new UnsupportedOperationException("PhysicsConstants 不可实例化");
    }

    // ==================== 基础运动速度 ====================

    /**
     * 玩家基础行走速度（方块/tick）。
     * <p>来源: minecraft.wiki - Player</p>
     */
    public static final double BASE_WALK_SPEED = 0.21585;

    /**
     * 玩家基础疾跑速度（方块/tick）。
     * <p>来源: minecraft.wiki - Sprinting</p>
     */
    public static final double BASE_SPRINT_SPEED = 0.2806;

    /**
     * 玩家疾跑跳跃时的水平速度（方块/tick）。
     * <p>来源: minecraft.wiki - Sprinting</p>
     */
    public static final double BASE_SPRINT_JUMP_SPEED = 0.35635;

    // ==================== 重力参数 ====================

    /**
     * 重力加速度（方块/tick^2）。
     * <p>每个 tick 施加的向下速度增量。</p>
     * <p>来源: minecraft.wiki - Transportation</p>
     */
    public static final double GRAVITY_ACCELERATION = 0.08;

    /**
     * 重力阻力系数。
     * <p>每 tick 垂直速度乘以此系数。</p>
     * <p>来源: minecraft.wiki - Entity motion</p>
     */
    public static final double GRAVITY_DRAG = 0.98;

    /**
     * 终端速度（方块/tick）。
     * <p>自由落体时最大垂直下降速度。</p>
     * <p>来源: minecraft.wiki - Fall damage</p>
     */
    public static final double TERMINAL_VELOCITY = 3.886;

    // ==================== 跳跃参数 ====================

    /**
     * 跳跃初始垂直速度（方块/tick）。
     * <p>来源: minecraft.wiki - Jumping</p>
     */
    public static final double JUMP_INITIAL_VELOCITY = 0.42;

    /**
     * 每级跳跃增益（Jump Boost）增加的初始速度。
     * <p>来源: minecraft.wiki - Jump Boost</p>
     */
    public static final double JUMP_BOOST_PER_LEVEL = 0.1;

    /**
     * 无跳跃增益时的最大跳跃高度（方块）。
     * <p>来源: minecraft.wiki - Jumping</p>
     */
    public static final double MAX_JUMP_HEIGHT_NO_BOOST = 1.2522;

    /**
     * 跳跃增益 I 时的最大跳跃高度（方块）。
     */
    public static final double MAX_JUMP_HEIGHT_BOOST_I = 1.518;

    /**
     * 跳跃增益 II 时的最大跳跃高度（方块）。
     */
    public static final double MAX_JUMP_HEIGHT_BOOST_II = 1.835;

    /**
     * 疾跑跳跃最大水平距离（方块）。
     * <p>来源: minecraft.wiki - Sprinting</p>
     */
    public static final double MAX_SPRINT_JUMP_DISTANCE = 4.317;

    // ==================== 碰撞箱参数 ====================

    /**
     * 自动台阶高度（方块）。
     * <p>玩家无需跳跃即可自动攀登的最大高度。</p>
     * <p>来源: minecraft.wiki - Player</p>
     */
    public static final double AUTO_STEP_HEIGHT = 0.6;

    /**
     * 玩家宽度（方块）。
     * <p>玩家碰撞箱的宽度。</p>
     * <p>来源: minecraft.wiki - Player</p>
     */
    public static final double PLAYER_WIDTH = 0.6;

    /**
     * 玩家高度（方块）。
     * <p>玩家碰撞箱的高度。</p>
     * <p>来源: minecraft.wiki - Player</p>
     */
    public static final double PLAYER_HEIGHT = 1.8;

    /**
     * 玩家眼睛高度（方块）。
     * <p>从脚底到眼睛的高度。</p>
     * <p>来源: minecraft.wiki - Player</p>
     */
    public static final double PLAYER_EYE_HEIGHT = 1.62;

    // ==================== 环境速度乘数 ====================

    /**
     * 冰面速度乘数。
     * <p>在冰面上行走时的速度增益。</p>
     * <p>来源: minecraft.wiki - Ice</p>
     */
    public static final double ICE_SPEED_MULTIPLIER = 1.4;

    /**
     * 蓝冰速度乘数。
     * <p>在蓝冰上行走时的速度增益。</p>
     * <p>来源: minecraft.wiki - Blue Ice</p>
     */
    public static final double BLUE_ICE_SPEED_MULTIPLIER = 1.6;

    /**
     * 海豚的恩惠（Dolphin's Grace）速度乘数。
     * <p>来源: minecraft.wiki - Dolphin's Grace</p>
     */
    public static final double DOLPHINS_GRACE_MULTIPLIER = 1.75;

    /**
     * 灵魂疾行（Soul Speed）基础速度乘数。
     * <p>来源: minecraft.wiki - Soul Speed</p>
     */
    public static final double SOUL_SPEED_BASE_MULTIPLIER = 1.3;

    /**
     * 灵魂疾行每级额外增加的乘数。
     */
    public static final double SOUL_SPEED_PER_LEVEL = 0.105;

    /**
     * 速度药水（Speed）每级速度增量（方块/tick）。
     * <p>来源: minecraft.wiki - Speed</p>
     */
    public static final double SPEED_POTION_PER_LEVEL = 0.2;

    /**
     * 潜行速度乘数。
     * <p>潜行时移动速度为基础速度的 30%。</p>
     * <p>来源: minecraft.wiki - Sneaking</p>
     */
    public static final double SNEAK_SPEED_MULTIPLIER = 0.3;

    /**
     * 使用物品时速度乘数。
     * <p>食用食物、使用盾牌等物品时移动速度。</p>
     * <p>来源: minecraft.wiki - Food</p>
     */
    public static final double USE_ITEM_SPEED_MULTIPLIER = 0.2;

    /**
     * 蜘蛛网速度乘数。
     * <p>在蜘蛛网中移动时的速度削减。</p>
     * <p>来源: minecraft.wiki - Cobweb</p>
     */
    public static final double COBWEB_SPEED_MULTIPLIER = 0.05;

    // ==================== 爬梯参数 ====================

    /**
     * 爬梯基础速度（方块/tick）。
     * <p>来源: minecraft.wiki - Ladder</p>
     */
    public static final double CLIMB_SPEED = 0.087;

    /**
     * 爬梯速度阈值（方块/tick）。
     * <p>低于此速度视为不在爬梯。</p>
     */
    public static final double CLIMB_SPEED_THRESHOLD = 0.15;

    // ==================== 鞘翅参数 ====================

    /**
     * 鞘翅最大等级提升速度（方块/tick）。
     * <p>鞘翅飞行时通过俯冲可获得的最大速度。</p>
     */
    public static final double ELYTRA_MAX_LEVEL_SPEED = 1.5;

    /**
     * 鞘翅烟花火箭加速速度（方块/tick）。
     * <p>使用烟花火箭时鞘翅获得的速度增量。</p>
     */
    public static final double ELYTRA_FIREWORK_BOOST = 1.675;

    /**
     * 鞘翅最大俯冲速度（方块/tick）。
     */
    public static final double ELYTRA_MAX_DIVE_SPEED = 3.365;

    /**
     * 鞘翅最小爬升速度（方块/tick）。
     * <p>俯仰角过大时失去高度的最小速度。</p>
     */
    public static final double ELYTRA_MIN_CLIMB_SPEED = 0.36;

    /**
     * 鞘翅摩擦系数。
     * <p>鞘翅飞行时每 tick 速度乘以此系数。</p>
     */
    public static final double ELYTRA_FRICTION = 0.99;

    // ==================== 击退参数 ====================

    /**
     * 最小击退速度（方块/tick）。
     * <p>低于此速度的击退不产生位移。</p>
     */
    public static final double MIN_KNOCKBACK_SPEED = 0.15;

    /**
     * 击退衰减系数。
     * <p>击退速度每 tick 乘以此系数。</p>
     */
    public static final double KNOCKBACK_DECAY = 0.8;

    // ==================== 水中参数 ====================

    /**
     * 水中游泳速度乘数。
     * <p>水中移动速度为基础速度的 50%。</p>
     * <p>来源: minecraft.wiki - Swimming</p>
     */
    public static final double WATER_SWIM_MULTIPLIER = 0.5;

    /**
     * 缓降（Slow Falling）重力乘数。
     * <p>缓降效果下重力加速度乘以此系数。</p>
     * <p>来源: minecraft.wiki - Slow Falling</p>
     */
    public static final double SLOW_FALLING_MULTIPLIER = 0.125;

    // ==================== 摔落参数 ====================

    /**
     * 摔落伤害阈值（方块）。
     * <p>超过此高度才产生摔落伤害。</p>
     * <p>来源: minecraft.wiki - Fall damage</p>
     */
    public static final double FALL_DAMAGE_THRESHOLD = 3.0;

    // ==================== 飘浮参数 ====================

    /**
     * 飘浮（Levitation）每级上升速度（方块/tick）。
     * <p>来源: minecraft.wiki - Levitation</p>
     */
    public static final double LEVITATION_SPEED_PER_LEVEL = 0.05;

    // ==================== 辅助方法 ====================

    /**
     * 模拟计算最大跳跃高度。
     * <p>
     * 给定初始垂直速度，模拟跳跃轨迹直到垂直速度降为 0，
     * 返回从起点到最高点的垂直高度差。
     * </p>
     *
     * @param initialVelocity 跳跃初始垂直速度（方块/tick）
     * @return 最大跳跃高度（方块）
     */
    public static double simulateMaxJumpHeight(double initialVelocity) {
        double height = 0.0;
        double velocity = initialVelocity;
        while (velocity > 0.0) {
            velocity = GRAVITY_DRAG * (velocity - GRAVITY_ACCELERATION);
            if (velocity > 0.0) {
                height += velocity;
            }
        }
        return height;
    }

    /**
     * 模拟跳跃轨迹，返回每 tick 的垂直位置偏移列表。
     * <p>
     * 给定初始垂直速度，模拟最多 {@code maxTicks} 个 tick 的跳跃轨迹。
     * 当垂直速度降至 0 以下时停止模拟。
     * </p>
     *
     * @param initialVelocity 跳跃初始垂直速度（方块/tick）
     * @param maxTicks         最大模拟 tick 数
     * @return 每 tick 的垂直位置偏移列表
     */
    public static List<Double> simulateJumpTrajectory(double initialVelocity, int maxTicks) {
        List<Double> trajectory = new ArrayList<>();
        double velocity = initialVelocity;
        for (int i = 0; i < maxTicks; i++) {
            velocity = GRAVITY_DRAG * (velocity - GRAVITY_ACCELERATION);
            trajectory.add(velocity);
            if (velocity <= 0.0) {
                break;
            }
        }
        return trajectory;
    }

    /**
     * 获取指定跳跃增益等级下的最大跳跃高度。
     * <p>
     * 使用 {@link #simulateMaxJumpHeight(double)} 进行精确模拟计算。
     * </p>
     *
     * @param jumpBoostLevel 跳跃增益等级（0 = 无增益，1 = Jump Boost I，2 = Jump Boost II）
     * @return 最大跳跃高度（方块）
     */
    public static double getMaxJumpHeight(int jumpBoostLevel) {
        double initialVelocity = JUMP_INITIAL_VELOCITY + jumpBoostLevel * JUMP_BOOST_PER_LEVEL;
        return simulateMaxJumpHeight(initialVelocity);
    }
}
