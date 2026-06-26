package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.physics.mlp.profile.PlayerBehaviorProfile;

/**
 * 玩家行为多模态特征提取器。
 * 从移动 + 战斗 + 建造 + 交互 + 网络 五个维度提取 72 维归一化特征。
 */
public final class BehaviorFeatureExtractor {
    public static final int FEATURE_COUNT = 72;

    // 各维度偏移和数量
    public static final int MOVEMENT_COUNT = 24;
    public static final int COMBAT_COUNT = 14;
    public static final int BUILDING_COUNT = 10;
    public static final int INTERACTION_COUNT = 10;
    public static final int NETWORK_COUNT = 10;

    public static final int COMBAT_OFFSET = MOVEMENT_COUNT;
    public static final int BUILDING_OFFSET = MOVEMENT_COUNT + COMBAT_COUNT;
    public static final int INTERACTION_OFFSET = BUILDING_OFFSET + BUILDING_COUNT;
    public static final int NETWORK_OFFSET = INTERACTION_OFFSET + INTERACTION_COUNT;

    /** 72 维特征的人类可读名称 */
    public static final String[] FEATURE_NAMES = {
        // 移动维度 (24)
        "水平速度", "Y轴速度", "预测Y速度", "速度药水", "跳跃增益",
        "跳跃阶段", "跳跃计时", "离地Tick", "跌落距离", "着地状态",
        "水中", "岩浆中", "潜行", "疾跑", "举盾",
        "冰面", "蓝冰", "飘浮", "缓降", "海豚恩惠",
        "灵魂疾行", "窗口平均水平速度", "窗口Y速度方差", "窗口着地比例",
        // 战斗维度 (14)
        "CPS均值", "CPS标准差", "攻击间隔均值", "攻击间隔标准差",
        "暴击率", "平均攻击距离", "瞄准平滑度", "最大连击数",
        "总攻击次数", "攻击/分钟", "偏航变化均值", "俯仰变化均值",
        "方向熵", "攻击节奏规律性",
        // 建造维度 (10)
        "放置间隔均值", "放置间隔标准差", "破坏间隔均值", "破坏间隔标准差",
        "方向一致性", "空中放置率", "单次放置数均值", "建造/分钟",
        "建造速度熵", "放置破坏比",
        // 交互维度 (10)
        "吃持续时间均值", "格挡持续时间均值", "使用物品间隔均值",
        "快速使用率", "吃东西/分钟", "格挡/分钟", "交互熵",
        "交互规律性", "物品切换频率", "物品使用多样性",
        // 网络维度 (10)
        "飞行包间隔均值", "飞行包间隔标准差", "包丢失率均值",
        "计时器余额均值", "包速率稳定性", "网络抖动",
        "延迟补偿量", "包序异常率", "飞行包规律性", "计时器漂移"
    };

    private BehaviorFeatureExtractor() {
        throw new UnsupportedOperationException();
    }

    public static double[] extract(PlayerPhysicsState physicsState, PlayerBehaviorProfile profile) {
        if (physicsState == null) physicsState = new PlayerPhysicsState();
        if (profile == null) profile = new PlayerBehaviorProfile();

        double[] f = new double[FEATURE_COUNT];
        int i = 0;

        // ==================== 移动维度 (24) ====================
        double hSpeed = Math.sqrt(
            physicsState.getVelocityX() * physicsState.getVelocityX()
            + physicsState.getVelocityZ() * physicsState.getVelocityZ()
        );
        f[i++] = clamp(hSpeed / 2.0, 0.0, 1.0);
        f[i++] = clamp(physicsState.getVelocityY(), -1.0, 1.0);
        f[i++] = clamp(physicsState.getPredictedVelocityY(), -1.0, 1.0);
        f[i++] = clamp(physicsState.getSpeedPotionLevel() / 5.0, 0.0, 1.0);
        f[i++] = clamp(physicsState.getJumpBoostLevel() / 5.0, 0.0, 1.0);
        f[i++] = physicsState.getJumpPhase().ordinal() / 5.0;
        f[i++] = clamp(physicsState.getJumpTickCount() / 30.0, 0.0, 1.0);
        f[i++] = clamp(physicsState.getTicksSinceLeftGround() / 100.0, 0.0, 1.0);
        f[i++] = clamp(physicsState.getServerFallDistance() / 50.0, 0.0, 1.0);
        f[i++] = physicsState.isClientOnGround() ? 1.0 : 0.0;
        f[i++] = physicsState.isInWater() ? 1.0 : 0.0;
        f[i++] = physicsState.isInLava() ? 1.0 : 0.0;
        f[i++] = physicsState.isSneaking() ? 1.0 : 0.0;
        f[i++] = physicsState.isSprinting() ? 1.0 : 0.0;
        f[i++] = physicsState.isBlocking() ? 1.0 : 0.0;
        f[i++] = physicsState.isOnIce() ? 1.0 : 0.0;
        f[i++] = physicsState.isOnBlueIce() ? 1.0 : 0.0;
        f[i++] = physicsState.hasLevitation() ? 1.0 : 0.0;
        f[i++] = physicsState.hasSlowFalling() ? 1.0 : 0.0;
        f[i++] = physicsState.hasDolphinsGrace() ? 1.0 : 0.0;
        f[i++] = physicsState.hasSoulSpeed() ? 1.0 : 0.0;

        var samples = physicsState.getMovementSamples();
        if (!samples.isEmpty()) {
            double avgH = 0.0, avgV = 0.0, varV = 0.0;
            int groundCount = 0, n = samples.size();
            for (var s : samples) {
                avgH += s.horizontalSpeed();
                avgV += s.deltaY();
                if (s.onGround()) groundCount++;
            }
            avgH /= n;
            avgV /= n;
            for (var s : samples) {
                double d = s.deltaY() - avgV;
                varV += d * d;
            }
            varV = Math.sqrt(varV / n);
            f[i++] = clamp(avgH / 2.0, 0.0, 1.0);
            f[i++] = clamp(varV / 0.5, 0.0, 1.0);
            f[i++] = groundCount / (double) n;
        } else {
            f[i++] = 0.0;
            f[i++] = 0.0;
            f[i++] = 0.0;
        }

        // ==================== 战斗维度 (14) ====================
        f[i++] = clamp(profile.getCombatCpsMean() / 25.0, 0.0, 1.0);           // CPS均值
        f[i++] = clamp(profile.getCombatCpsStd() / 10.0, 0.0, 1.0);             // CPS标准差
        f[i++] = clamp(profile.getCombatIntervalMean() / 1000.0, 0.0, 1.0);      // 攻击间隔均值
        f[i++] = clamp(profile.getCombatIntervalStd() / 500.0, 0.0, 1.0);        // 攻击间隔标准差
        f[i++] = profile.getCritRate();                                         // 暴击率
        f[i++] = clamp(profile.getReachMean() / 6.0, 0.0, 1.0);                 // 平均攻击距离
        f[i++] = profile.getAimSmoothness();                                    // 瞄准平滑度
        f[i++] = clamp(profile.getComboCount() / 20.0, 0.0, 1.0);              // 最大连击数
        f[i++] = clamp(profile.getTotalAttacks() / 100.0, 0.0, 1.0);           // 总攻击次数
        long sessionMin = Math.max(profile.getSessionDurationMinutes(), 1);
        f[i++] = clamp(profile.getTotalAttacks() / (double) sessionMin / 60.0, 0.0, 1.0); // 攻击/分钟
        f[i++] = 0.0; // 偏航变化均值 (占位)
        f[i++] = 0.0; // 俯仰变化均值 (占位)
        f[i++] = 0.0; // 方向熵 (占位)
        f[i++] = clamp(1.0 - profile.getCombatIntervalStd() / 300.0, 0.0, 1.0); // 攻击节奏规律性

        // ==================== 建造维度 (10) ====================
        f[i++] = clamp(profile.getPlaceIntervalMean() / 500.0, 0.0, 1.0);        // 放置间隔均值
        f[i++] = clamp(profile.getPlaceIntervalStd() / 200.0, 0.0, 1.0);         // 放置间隔标准差
        f[i++] = clamp(profile.getBreakIntervalMean() / 1000.0, 0.0, 1.0);        // 破坏间隔均值
        f[i++] = clamp(profile.getBreakIntervalMean() / 500.0, 0.0, 1.0);         // 破坏间隔标准差
        f[i++] = (profile.getDirectionConsistencyMean() + 1.0) / 2.0;            // 方向一致性
        f[i++] = profile.getAirPlaceRate();                                      // 空中放置率
        f[i++] = 0.0; // 单次放置数均值
        f[i++] = clamp(profile.getTotalBlocksPlaced() / (double) sessionMin / 30.0, 0.0, 1.0); // 建造/分钟
        f[i++] = clamp(1.0 - profile.getPlaceIntervalStd() / 200.0, 0.0, 1.0);   // 建造速度熵
        double breakRatio = profile.getTotalBlocksBroken() > 0
            ? (double) profile.getTotalBlocksPlaced() / profile.getTotalBlocksBroken() : 0.0;
        f[i++] = clamp(breakRatio / 5.0, 0.0, 1.0);                             // 放置破坏比

        // ==================== 交互维度 (10) ====================
        f[i++] = clamp(profile.getEatDurationMean() / 32.0, 0.0, 1.0);           // 吃持续时间均值
        f[i++] = clamp(profile.getBlockDurationMean() / 20.0, 0.0, 1.0);         // 格挡持续时间均值
        f[i++] = clamp(profile.getUseItemIntervalMean() / 1000.0, 0.0, 1.0);     // 使用物品间隔均值
        f[i++] = profile.getFastUseRate();                                       // 快速使用率
        f[i++] = clamp(profile.getTotalEats() / (double) sessionMin / 5.0, 0.0, 1.0); // 吃东西/分钟
        f[i++] = 0.0; // 格挡/分钟
        f[i++] = 0.0; // 交互熵
        f[i++] = clamp(1.0 - profile.getUseItemIntervalMean() / 500.0, 0.0, 1.0); // 交互规律性
        f[i++] = 0.0; // 物品切换频率
        f[i++] = 0.0; // 物品使用多样性

        // ==================== 网络维度 (10) ====================
        f[i++] = clamp(profile.getFlyingIntervalMean() / 100.0, 0.0, 1.0);       // 飞行包间隔均值
        f[i++] = clamp(profile.getFlyingIntervalStd() / 50.0, 0.0, 1.0);         // 飞行包间隔标准差
        f[i++] = clamp(profile.getPacketLossMean(), 0.0, 1.0);                    // 包丢失率均值
        f[i++] = clamp(profile.getTimerBalanceMean() / 1000.0, -1.0, 1.0);        // 计时器余额均值
        f[i++] = clamp(1.0 - profile.getFlyingIntervalStd() / 50.0, 0.0, 1.0);   // 包速率稳定性
        f[i++] = clamp(profile.getFlyingIntervalStd() / 30.0, 0.0, 1.0);          // 网络抖动
        f[i++] = 0.0; // 延迟补偿量
        f[i++] = 0.0; // 包序异常率
        f[i++] = clamp(1.0 - profile.getFlyingIntervalStd() / 50.0, 0.0, 1.0);   // 飞行包规律性
        f[i++] = clamp(profile.getTimerBalanceMean() / 500.0, -1.0, 1.0);         // 计时器漂移

        while (i < FEATURE_COUNT) {
            f[i++] = 0.0;
        }

        return f;
    }

    /**
     * 从完整特征向量中切片出战斗维度特征(14维)。
     * 供 CombatMLP 直接输入使用。
     */
    public static double[] extractCombatSlice(double[] fullFeatures) {
        double[] combat = new double[COMBAT_COUNT];
        System.arraycopy(fullFeatures, COMBAT_OFFSET, combat, 0, COMBAT_COUNT);
        return combat;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
