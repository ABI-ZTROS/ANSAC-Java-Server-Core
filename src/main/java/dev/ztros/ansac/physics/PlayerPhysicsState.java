package dev.ztros.ansac.physics;

import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 每玩家完整物理状态追踪器。
 * <p>
 * 整合了原来分散在 10+ 个碎片化追踪器中的物理状态数据，
 * 提供统一的玩家物理状态管理。
 * </p>
 * <p>
 * <b>线程安全说明：</b> 所有读写操作必须在玩家所属的实体线程上执行
 * （Folia 的 EntityScheduler），以确保线程安全。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public class PlayerPhysicsState {

    // ==================== 跳跃状态机枚举 ====================

    /**
     * 跳跃状态机阶段枚举。
     * <p>
     * 定义了玩家一次完整跳跃的各个阶段，用于跳跃状态机的状态转移。
     * </p>
     * <ul>
     *   <li>{@code NONE} - 无跳跃活动</li>
     *   <li>{@code ASCENDING} - 上升阶段（垂直速度为正）</li>
     *   <li>{@code APEX} - 最高点（垂直速度接近 0）</li>
     *   <li>{@code DESCENDING} - 下降阶段（垂直速度为负）</li>
     *   <li>{@code LANDED} - 已落地（等待确认）</li>
     * </ul>
     */
    public enum JumpPhase {
        /** 无跳跃活动 */
        NONE,
        /** 上升阶段 */
        ASCENDING,
        /** 最高点 */
        APEX,
        /** 下降阶段 */
        DESCENDING,
        /** 已落地 */
        LANDED
    }

    // ==================== 移动采样记录 ====================

    /**
     * 移动采样记录，存储单次移动事件的差值和状态。
     * <p>用于滑动窗口分析和速度模式检测。</p>
     *
     * @param deltaX      X 轴位移差值
     * @param deltaY      Y 轴位移差值
     * @param deltaZ      Z 轴位移差值
     * @param horizontalSpeed 水平合成速度
     * @param onGround    此采样时是否在地面上
     * @param timestamp   采样时间戳（毫秒）
     */
    public record MovementSample(
            double deltaX,
            double deltaY,
            double deltaZ,
            double horizontalSpeed,
            boolean onGround,
            long timestamp
    ) {}

    // ==================== 位置和速度 ====================

    /** 上一个位置（移动前） */
    private Location previousLocation;

    /** 当前位置（移动后） */
    private Location currentLocation;

    /** 安全位置（用于回退） */
    private Location safeLocation;

    /** X 轴速度（方块/tick） */
    private double velocityX;

    /** Y 轴速度（方块/tick） */
    private double velocityY;

    /** Z 轴速度（方块/tick） */
    private double velocityZ;

    /** 预测的 Y 轴速度（基于物理模型计算） */
    private double predictedVelocityY;

    // ==================== 跳跃状态 ====================

    /** 当前跳跃阶段 */
    private JumpPhase jumpPhase;

    /** 跳跃起始 Y 坐标 */
    private double jumpStartY;

    /** 跳跃最高点 Y 坐标 */
    private double jumpPeakY;

    /** 起跳时的水平速度 */
    private double jumpTakeoffHorizontalSpeed;

    /** 当前跳跃持续的 tick 数 */
    private int jumpTickCount;

    /** 上一 tick 的 deltaY */
    private double previousDeltaY;

    /** 跳跃起始位置快照 */
    private Location jumpStartLocation;

    // ==================== 地面状态 ====================

    /** 客户端报告的着地状态 */
    private boolean clientOnGround;

    /** 服务端验证的着地状态 */
    private boolean serverVerifiedGround;

    /** 上次服务端验证的地面 Y 坐标 */
    private double lastVerifiedGroundY;

    // ==================== 下落追踪 ====================

    /** 服务端计算的下落距离 */
    private double serverFallDistance;

    /** 离开地面后的 tick 数 */
    private int ticksSinceLeftGround;

    // ==================== 鞘翅状态 ====================

    /** 是否正在滑翔 */
    private boolean isGliding;

    /** 上次滑翔水平速度 */
    private double lastGlideHorizontalSpeed;

    /** 上次滑翔偏航角 */
    private float lastGlideYaw;

    /** 上次滑翔俯仰角 */
    private float lastGlidePitch;

    /** 鞘翅烟花火箭冷却 tick 数 */
    private int elytraFireworkCooldownTicks;

    // ==================== Buff 状态 ====================

    /** 速度药水等级 */
    private int speedPotionLevel;

    /** 跳跃增益等级 */
    private int jumpBoostLevel;

    /** 是否拥有飘浮效果 */
    private boolean hasLevitation;

    /** 飘浮效果等级 */
    private int levitationLevel;

    /** 是否拥有缓降效果 */
    private boolean hasSlowFalling;

    /** 是否拥有海豚的恩惠效果 */
    private boolean hasDolphinsGrace;

    /** 是否拥有灵魂疾行效果 */
    private boolean hasSoulSpeed;

    /** 灵魂疾行效果等级 */
    private int soulSpeedLevel;

    // ==================== 玩家状态标志 ====================

    /** 是否在水中 */
    private boolean inWater;

    /** 是否在熔岩中 */
    private boolean inLava;

    /** 是否正在爬梯 */
    private boolean isClimbing;

    /** 是否潜行 */
    private boolean isSneaking;

    /** 是否疾跑 */
    private boolean isSprinting;

    /** 是否格挡（使用盾牌） */
    private boolean isBlocking;

    // ==================== 环境状态 ====================

    /** 是否站在冰面上 */
    private boolean onIce;

    /** 是否站在蓝冰上 */
    private boolean onBlueIce;

    /** 是否接近地面（用于鞘翅/落地检测） */
    private boolean nearGround;

    /** 是否站在蜘蛛网中 */
    private boolean inCobweb;

    /** 是否站在灵魂沙上 */
    private boolean onSoulSand;

    /** 是否站在粘液块上 */
    private boolean onSlimeBlock;

    /** 是否站在蜂蜜块上 */
    private boolean onHoneyBlock;

    /** 是否站在粉雪中 */
    private boolean inPowderSnow;

    /** 是否在泡泡柱上方（海龟蛋/海底神殿下方） */
    private boolean aboveBubbleColumn;

    /** 头顶方块数量（0=无阻挡，1=低通道，2+=完全封闭） */
    private int headBlockCount;

    /** 脚下地面方块类型（用于更精细的物理计算） */
    private Material groundMaterial;

    /** 上次击退产生的速度向量大小（方块/tick），0 = 无击退 */
    private double knockbackMagnitude;

    /** 上次击退方向（度数） */
    private float knockbackYaw;

    // ==================== 历史采样 ====================

    /** 移动采样滑动窗口（最多 20 个采样） */
    private final Deque<MovementSample> movementSamples;

    // ==================== MLP 推理评分 ====================
    /** MovementMLP 正常度评分 (0-1)，0.5 为未知/中性 */
    private double lastNormalScore = 0.5;

    /** AnomalyFusion 融合异常评分 (0-1)，0 为正常，1 为严重异常 */
    private double lastAnomalyScore = 0.0;

    // ==================== 时间追踪 ====================

    /** 上次着地的时间戳（毫秒） */
    private long lastGroundTime;

    /** 上次受击退的时间戳（毫秒） */
    private long lastKnockbackTime;

    // ==================== 构造函数 ====================

    /**
     * 创建默认的物理状态。
     */
    public PlayerPhysicsState() {
        this.jumpPhase = JumpPhase.NONE;
        this.movementSamples = new ArrayDeque<>(20);
        reset();
    }

    // ==================== 核心更新方法 ====================

    /**
     * 从玩家移动事件更新物理状态。
     * <p>
     * 每次玩家移动时调用，更新位置、速度、地面状态，
     * 并驱动跳跃状态机和下落追踪的更新。
     * </p>
     *
     * @param player 玩家实例
     * @param from   移动前位置
     * @param to     移动后位置
     * @param now    当前时间戳（毫秒）
     */
    public void updateFromPlayer(Player player, Location from, Location to, long now) {
        // 保存上一个位置
        this.previousLocation = this.currentLocation != null ? this.currentLocation.clone() : from.clone();

        // 更新当前位置
        this.currentLocation = to.clone();

        // 计算速度（方块/tick，假设每个移动包为一个 tick）
        this.velocityX = to.getX() - from.getX();
        this.velocityY = to.getY() - from.getY();
        this.velocityZ = to.getZ() - from.getZ();

        // 更新地面状态
        this.clientOnGround = player.isOnGround();

        // 更新速度预测
        this.predictedVelocityY = PhysicsEngine.predictNextVerticalVelocity(this.velocityY);

        // 更新安全位置（当在地面上时）
        if (this.clientOnGround) {
            this.safeLocation = to.clone();
            this.lastGroundTime = now;
        }

        // 更新跳跃状态机
        updateJumpStateMachine();

        // 更新下落追踪
        updateFallTracking();

        // 刷新 Buff 效果
        refreshEffects(player);

        // 刷新环境状态
        refreshEnvironment(player);

        // 记录采样
        recordSample(now);

        // 更新状态标志
        this.isSneaking = player.isSneaking();
        this.isSprinting = player.isSprinting();
        this.isGliding = player.isGliding();
        this.inWater = player.isInWater();
        this.inLava = player.isInLava();
    }

    /**
     * 刷新玩家的 Buff 效果状态。
     * <p>
     * 从玩家的活跃药水效果中提取所有影响物理运动的 Buff 信息。
     * 使用 {@link ServerVersionAdapter} 获取版本适配的药水效果类型。
     * </p>
     *
     * @param player 玩家实例
     */
    public void refreshEffects(Player player) {
        // 速度药水
        PotionEffectType speedType = PotionEffectType.SPEED;
        if (player.hasPotionEffect(speedType)) {
            this.speedPotionLevel = player.getPotionEffect(speedType).getAmplifier() + 1;
        } else {
            this.speedPotionLevel = 0;
        }

        // 跳跃增益
        PotionEffectType jumpType = ServerVersionAdapter.getJumpBoost();
        if (jumpType != null && player.hasPotionEffect(jumpType)) {
            this.jumpBoostLevel = player.getPotionEffect(jumpType).getAmplifier() + 1;
        } else {
            this.jumpBoostLevel = 0;
        }

        // 飘浮
        PotionEffectType levitationType = ServerVersionAdapter.getLevitation();
        if (levitationType != null && player.hasPotionEffect(levitationType)) {
            this.hasLevitation = true;
            this.levitationLevel = player.getPotionEffect(levitationType).getAmplifier() + 1;
        } else {
            this.hasLevitation = false;
            this.levitationLevel = 0;
        }

        // 缓降
        PotionEffectType slowFallingType = PotionEffectType.SLOW_FALLING;
        try {
            if (player.hasPotionEffect(slowFallingType)) {
                this.hasSlowFalling = true;
            } else {
                this.hasSlowFalling = false;
            }
        } catch (Exception e) {
            this.hasSlowFalling = false;
        }

        // 海豚的恩惠
        PotionEffectType dolphinsGraceType = ServerVersionAdapter.getDolphinsGrace();
        if (dolphinsGraceType != null && player.hasPotionEffect(dolphinsGraceType)) {
            this.hasDolphinsGrace = true;
        } else {
            this.hasDolphinsGrace = false;
        }

        // 灵魂疾行
        PotionEffectType soulSpeedType = ServerVersionAdapter.getSoulSpeed();
        if (soulSpeedType != null && player.hasPotionEffect(soulSpeedType)) {
            this.hasSoulSpeed = true;
            this.soulSpeedLevel = player.getPotionEffect(soulSpeedType).getAmplifier() + 1;
        } else {
            this.hasSoulSpeed = false;
            this.soulSpeedLevel = 0;
        }
    }

    /**
     * 刷新环境状态。
     * <p>
     * 检测玩家脚下及周围的方块类型，更新冰面、蓝冰、攀爬、格挡等环境标志。
     * </p>
     *
     * @param player 玩家实例
     */
    public void refreshEnvironment(Player player) {
        if (currentLocation == null) return;

        // 脚下方块
        Block belowBlock = currentLocation.clone().subtract(0, 0.01, 0).getBlock();
        Material belowType = belowBlock.getType();
        this.groundMaterial = belowType;

        // 冰面检测
        this.onIce = (belowType == Material.ICE || belowType == Material.FROSTED_ICE);
        this.onBlueIce = (belowType == Material.BLUE_ICE);

        // 蜘蛛网
        this.inCobweb = (belowType == Material.COBWEB || belowType == Material.MANGROVE_ROOTS);

        // 灵魂沙
        this.onSoulSand = (belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL);

        // 粘液块
        this.onSlimeBlock = (belowType == Material.SLIME_BLOCK);

        // 蜂蜜块
        this.onHoneyBlock = (belowType == Material.HONEY_BLOCK);

        // 粉雪 — 需要检查玩家所在方块（脚到头顶）
        Block feetBlock = currentLocation.getBlock();
        this.inPowderSnow = (feetBlock.getType() == Material.POWDER_SNOW);

        // 泡泡柱 — 检查脚下一格和脚下两格
        Block below1 = currentLocation.clone().subtract(0, 1, 0).getBlock();
        Block below2 = currentLocation.clone().subtract(0, 2, 0).getBlock();
        this.aboveBubbleColumn = (below1.getType() == Material.BUBBLE_COLUMN
                || below2.getType() == Material.BUBBLE_COLUMN);

        // 头顶方块检测（检查脚+1 到 脚+2 的方块）
        int headCount = 0;
        Block headBlock1 = currentLocation.clone().add(0, 1, 0).getBlock();
        Block headBlock2 = currentLocation.clone().add(0, 2, 0).getBlock();
        if (isSolidForHead(headBlock1.getType())) headCount++;
        if (isSolidForHead(headBlock2.getType())) headCount++;
        this.headBlockCount = headCount;

        // 爬梯检测
        this.isClimbing = (belowType == Material.LADDER
                || belowType == Material.VINE
                || belowType == Material.SCAFFOLDING
                || belowType == Material.TWISTING_VINES
                || belowType == Material.WEEPING_VINES);

        // 接近地面检测
        this.nearGround = PhysicsEngine.verifyGroundState(currentLocation.clone().subtract(0, 2.0, 0));

        // 格挡检测
        this.isBlocking = player.isBlocking();

        // 击退衰减：击退速度每 tick 乘以衰减系数
        if (this.knockbackMagnitude > 0.001) {
            this.knockbackMagnitude *= PhysicsConstants.KNOCKBACK_DECAY;
        } else {
            this.knockbackMagnitude = 0.0;
        }
    }

    /**
     * 判断方块类型是否算作头顶阻挡（顶格跳加速判断）。
     */
    private static boolean isSolidForHead(Material mat) {
        if (mat.isAir()) return false;
        if (mat == Material.WATER || mat == Material.LAVA) return false;
        if (mat == Material.TORCH || mat == Material.SOUL_TORCH) return false;
        if (mat == Material.REDSTONE_TORCH || mat == Material.REDSTONE_WALL_TORCH) return false;
        if (mat == Material.WALL_TORCH) return false;
        if (mat == Material.FERN || mat == Material.LARGE_FERN) return false;
        if (mat == Material.DEAD_BUSH || mat == Material.TALL_GRASS) return false;
        return true;
    }

    /**
     * 更新跳跃状态机。
     * <p>
     * 根据当前物理状态和上一 tick 的状态，按照以下规则转移跳跃阶段：
     * <ul>
     *   <li>NONE -&gt; ASCENDING: wasOnGround &amp;&amp; !clientOnGround &amp;&amp; deltaY &gt; 0.12</li>
     *   <li>ASCENDING -&gt; APEX: prevDeltaY &gt; 0.02 &amp;&amp; deltaY &lt;= 0.02</li>
     *   <li>ASCENDING/APEX -&gt; DESCENDING: deltaY &lt; -0.02</li>
     *   <li>DESCENDING -&gt; LANDED: clientOnGround || ticks &gt; 30</li>
     *   <li>LANDED -&gt; NONE: 下一 tick</li>
     *   <li>任意 -&gt; NONE: ticks &gt; 30 || isGliding || hasLevitation || inWater</li>
     * </ul>
     * </p>
     */
    public void updateJumpStateMachine() {
        double deltaY = this.velocityY;
        boolean wasOnGround = (jumpPhase != JumpPhase.NONE && jumpPhase != JumpPhase.LANDED)
                ? ticksSinceLeftGround == 0
                : clientOnGround;

        // 记录跳跃前的着地状态
        boolean wasGroundBefore = (previousLocation != null)
                && (serverVerifiedGround || clientOnGround);

        // 任意状态 -> NONE: 异常中断条件
        if (jumpTickCount > 30 || isGliding || hasLevitation || inWater) {
            jumpPhase = JumpPhase.NONE;
            jumpTickCount = 0;
            return;
        }

        switch (jumpPhase) {
            case NONE:
                // NONE -> ASCENDING: 从地面起跳，且 deltaY 足够大
                if (wasGroundBefore && !clientOnGround && deltaY > 0.12) {
                    jumpPhase = JumpPhase.ASCENDING;
                    jumpStartY = currentLocation != null ? currentLocation.getY() : 0.0;
                    jumpPeakY = jumpStartY;
                    jumpStartLocation = currentLocation != null ? currentLocation.clone() : null;
                    jumpTickCount = 0;
                    // 记录起跳水平速度
                    double hSpeed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
                    jumpTakeoffHorizontalSpeed = hSpeed;
                }
                break;

            case ASCENDING:
                jumpTickCount++;

                // ASCENDING -> APEX: 垂直速度从正变为接近 0
                if (previousDeltaY > 0.02 && deltaY <= 0.02) {
                    jumpPhase = JumpPhase.APEX;
                    if (currentLocation != null) {
                        jumpPeakY = currentLocation.getY();
                    }
                }
                // ASCENDING -> DESCENDING: 直接开始下降（跳过了 APEX）
                else if (deltaY < -0.02) {
                    jumpPhase = JumpPhase.DESCENDING;
                    if (currentLocation != null) {
                        jumpPeakY = Math.max(jumpPeakY, currentLocation.getY());
                    }
                }
                break;

            case APEX:
                jumpTickCount++;

                // APEX -> DESCENDING: 开始下降
                if (deltaY < -0.02) {
                    jumpPhase = JumpPhase.DESCENDING;
                }
                break;

            case DESCENDING:
                jumpTickCount++;

                // DESCENDING -> LANDED: 着地或超时
                if (clientOnGround || jumpTickCount > 30) {
                    jumpPhase = JumpPhase.LANDED;
                }
                break;

            case LANDED:
                // LANDED -> NONE: 下一 tick 重置
                jumpPhase = JumpPhase.NONE;
                jumpTickCount = 0;
                break;
        }

        // 更新 previousDeltaY
        previousDeltaY = deltaY;
    }

    /**
     * 更新下落追踪。
     * <p>
     * 追踪玩家离开地面后的 tick 数和累计下落距离。
     * </p>
     */
    public void updateFallTracking() {
        if (clientOnGround || serverVerifiedGround) {
            // 在地面上，重置下落追踪
            ticksSinceLeftGround = 0;
            serverFallDistance = 0.0;
            lastVerifiedGroundY = currentLocation != null ? currentLocation.getY() : 0.0;
        } else {
            // 在空中，递增 tick 和累计下落距离
            ticksSinceLeftGround++;

            if (currentLocation != null && lastVerifiedGroundY != Double.MIN_VALUE) {
                double currentY = currentLocation.getY();
                double fall = lastVerifiedGroundY - currentY;
                if (fall > 0) {
                    serverFallDistance = fall;
                }
            }
        }
    }

    /**
     * 记录移动采样到滑动窗口。
     * <p>
     * 维护最多 20 个采样的滑动窗口，超出时自动移除最旧的采样。
     * </p>
     *
     * @param now 当前时间戳（毫秒）
     */
    public void recordSample(long now) {
        double hSpeed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        MovementSample sample = new MovementSample(
                velocityX, velocityY, velocityZ,
                hSpeed, clientOnGround, now
        );

        movementSamples.addLast(sample);

        // 保持最多 20 个采样
        while (movementSamples.size() > 20) {
            movementSamples.removeFirst();
        }
    }

    /**
     * 重置所有物理状态到默认值。
     * <p>
     * 通常在玩家退出或重新登录时调用。
     * </p>
     */
    public void reset() {
        this.previousLocation = null;
        this.currentLocation = null;
        this.safeLocation = null;

        this.velocityX = 0.0;
        this.velocityY = 0.0;
        this.velocityZ = 0.0;
        this.predictedVelocityY = 0.0;

        this.jumpPhase = JumpPhase.NONE;
        this.jumpStartY = 0.0;
        this.jumpPeakY = 0.0;
        this.jumpTakeoffHorizontalSpeed = 0.0;
        this.jumpTickCount = 0;
        this.previousDeltaY = 0.0;
        this.jumpStartLocation = null;

        this.clientOnGround = false;
        this.serverVerifiedGround = false;
        this.lastVerifiedGroundY = Double.MIN_VALUE;

        this.serverFallDistance = 0.0;
        this.ticksSinceLeftGround = 0;

        this.isGliding = false;
        this.lastGlideHorizontalSpeed = 0.0;
        this.lastGlideYaw = 0.0f;
        this.lastGlidePitch = 0.0f;
        this.elytraFireworkCooldownTicks = 0;

        this.speedPotionLevel = 0;
        this.jumpBoostLevel = 0;
        this.hasLevitation = false;
        this.levitationLevel = 0;
        this.hasSlowFalling = false;
        this.hasDolphinsGrace = false;
        this.hasSoulSpeed = false;
        this.soulSpeedLevel = 0;

        this.inWater = false;
        this.inLava = false;
        this.isClimbing = false;
        this.isSneaking = false;
        this.isSprinting = false;
        this.isBlocking = false;

        this.onIce = false;
        this.onBlueIce = false;
        this.nearGround = false;
        this.inCobweb = false;
        this.onSoulSand = false;
        this.onSlimeBlock = false;
        this.onHoneyBlock = false;
        this.inPowderSnow = false;
        this.aboveBubbleColumn = false;
        this.headBlockCount = 0;
        this.groundMaterial = Material.AIR;
        this.knockbackMagnitude = 0.0;
        this.knockbackYaw = 0.0f;

        this.movementSamples.clear();

        this.lastGroundTime = 0;
        this.lastKnockbackTime = 0;
        this.lastNormalScore = 0.5;
    }

    // ==================== Getter 方法 ====================

    public Location getPreviousLocation() { return previousLocation; }
    public Location getCurrentLocation() { return currentLocation; }
    public Location getSafeLocation() { return safeLocation; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public double getVelocityZ() { return velocityZ; }
    public double getPredictedVelocityY() { return predictedVelocityY; }
    public JumpPhase getJumpPhase() { return jumpPhase; }
    public double getJumpStartY() { return jumpStartY; }
    public double getJumpPeakY() { return jumpPeakY; }
    public double getJumpTakeoffHorizontalSpeed() { return jumpTakeoffHorizontalSpeed; }
    public int getJumpTickCount() { return jumpTickCount; }
    public double getPreviousDeltaY() { return previousDeltaY; }
    public Location getJumpStartLocation() { return jumpStartLocation; }
    public boolean isClientOnGround() { return clientOnGround; }
    public boolean isServerVerifiedGround() { return serverVerifiedGround; }
    public double getLastVerifiedGroundY() { return lastVerifiedGroundY; }
    public double getServerFallDistance() { return serverFallDistance; }
    public int getTicksSinceLeftGround() { return ticksSinceLeftGround; }
    public boolean isGliding() { return isGliding; }
    public double getLastGlideHorizontalSpeed() { return lastGlideHorizontalSpeed; }
    public float getLastGlideYaw() { return lastGlideYaw; }
    public float getLastGlidePitch() { return lastGlidePitch; }
    public int getElytraFireworkCooldownTicks() { return elytraFireworkCooldownTicks; }
    public int getSpeedPotionLevel() { return speedPotionLevel; }
    public int getJumpBoostLevel() { return jumpBoostLevel; }
    public boolean hasLevitation() { return hasLevitation; }
    public int getLevitationLevel() { return levitationLevel; }
    public boolean hasSlowFalling() { return hasSlowFalling; }
    public boolean hasDolphinsGrace() { return hasDolphinsGrace; }
    public boolean hasSoulSpeed() { return hasSoulSpeed; }
    public int getSoulSpeedLevel() { return soulSpeedLevel; }
    public boolean isInWater() { return inWater; }
    public boolean isInLava() { return inLava; }
    public boolean isClimbing() { return isClimbing; }
    public boolean isSneaking() { return isSneaking; }
    public boolean isSprinting() { return isSprinting; }
    public boolean isBlocking() { return isBlocking; }
    public boolean isOnIce() { return onIce; }
    public boolean isOnBlueIce() { return onBlueIce; }
    public boolean isNearGround() { return nearGround; }
    public boolean isInCobweb() { return inCobweb; }
    public boolean isOnSoulSand() { return onSoulSand; }
    public boolean isOnSlimeBlock() { return onSlimeBlock; }
    public boolean isOnHoneyBlock() { return onHoneyBlock; }
    public boolean isInPowderSnow() { return inPowderSnow; }
    public boolean isAboveBubbleColumn() { return aboveBubbleColumn; }
    public int getHeadBlockCount() { return headBlockCount; }
    public Material getGroundMaterial() { return groundMaterial; }
    public double getKnockbackMagnitude() { return knockbackMagnitude; }
    public float getKnockbackYaw() { return knockbackYaw; }
    public Deque<MovementSample> getMovementSamples() { return movementSamples; }
    public long getLastGroundTime() { return lastGroundTime; }
    public long getLastKnockbackTime() { return lastKnockbackTime; }

    // ==================== Setter 方法 ====================

    public void setServerVerifiedGround(boolean serverVerifiedGround) {
        this.serverVerifiedGround = serverVerifiedGround;
    }

    public void setSafeLocation(Location safeLocation) {
        this.safeLocation = safeLocation != null ? safeLocation.clone() : null;
    }

    public void setLastGlideHorizontalSpeed(double lastGlideHorizontalSpeed) {
        this.lastGlideHorizontalSpeed = lastGlideHorizontalSpeed;
    }

    public void setLastGlideYaw(float lastGlideYaw) {
        this.lastGlideYaw = lastGlideYaw;
    }

    public void setLastGlidePitch(float lastGlidePitch) {
        this.lastGlidePitch = lastGlidePitch;
    }

    public void setElytraFireworkCooldownTicks(int elytraFireworkCooldownTicks) {
        this.elytraFireworkCooldownTicks = elytraFireworkCooldownTicks;
    }

    public void setLastKnockbackTime(long lastKnockbackTime) {
        this.lastKnockbackTime = lastKnockbackTime;
    }

    public void setKnockbackMagnitude(double magnitude) {
        this.knockbackMagnitude = magnitude;
    }

    public void setKnockbackYaw(float yaw) {
        this.knockbackYaw = yaw;
    }

    public double getLastNormalScore() { return lastNormalScore; }
    public void setLastNormalScore(double lastNormalScore) { this.lastNormalScore = lastNormalScore; }

    public double getLastAnomalyScore() { return lastAnomalyScore; }
    public void setLastAnomalyScore(double lastAnomalyScore) { this.lastAnomalyScore = lastAnomalyScore; }

    public void setOnGround(boolean onGround) {
        this.clientOnGround = onGround;
    }
}
