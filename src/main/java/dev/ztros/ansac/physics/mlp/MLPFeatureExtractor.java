package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;

/**
 * 从 PlayerPhysicsState 提取归一化特征向量，供 MLP 使用。
 */
public final class MLPFeatureExtractor {
    public static final int FEATURE_COUNT = 24;

    /** 24 维特征的人类可读名称 */
    public static final String[] FEATURE_NAMES = {
        "水平速度", "Y轴速度", "预测Y速度", "速度药水", "跳跃增益",
        "跳跃阶段", "跳跃计时", "离地Tick", "跌落距离", "着地状态",
        "水中", "岩浆中", "潜行", "疾跑", "举盾",
        "冰面", "蓝冰", "飘浮", "缓降", "海豚恩惠",
        "灵魂疾行", "窗口平均水平速度", "窗口Y速度方差", "窗口着地比例"
    };

    private MLPFeatureExtractor() {
        throw new UnsupportedOperationException();
    }

    public static double[] extract(PlayerPhysicsState state) {
        if (state == null) {
            return new double[FEATURE_COUNT];
        }

        double[] f = new double[FEATURE_COUNT];
        int i = 0;

        double hSpeed = Math.sqrt(
            state.getVelocityX() * state.getVelocityX()
            + state.getVelocityZ() * state.getVelocityZ()
        );
        f[i++] = clamp(hSpeed / 2.0, 0.0, 1.0);
        f[i++] = clamp(state.getVelocityY(), -1.0, 1.0);
        f[i++] = clamp(state.getPredictedVelocityY(), -1.0, 1.0);
        f[i++] = clamp(state.getSpeedPotionLevel() / 5.0, 0.0, 1.0);
        f[i++] = clamp(state.getJumpBoostLevel() / 5.0, 0.0, 1.0);
        f[i++] = state.getJumpPhase().ordinal() / 5.0;
        f[i++] = clamp(state.getJumpTickCount() / 30.0, 0.0, 1.0);
        f[i++] = clamp(state.getTicksSinceLeftGround() / 100.0, 0.0, 1.0);
        f[i++] = clamp(state.getServerFallDistance() / 50.0, 0.0, 1.0);
        f[i++] = state.isClientOnGround() ? 1.0 : 0.0;
        f[i++] = state.isInWater() ? 1.0 : 0.0;
        f[i++] = state.isInLava() ? 1.0 : 0.0;
        f[i++] = state.isSneaking() ? 1.0 : 0.0;
        f[i++] = state.isSprinting() ? 1.0 : 0.0;
        f[i++] = state.isBlocking() ? 1.0 : 0.0;
        f[i++] = state.isOnIce() ? 1.0 : 0.0;
        f[i++] = state.isOnBlueIce() ? 1.0 : 0.0;
        f[i++] = state.hasLevitation() ? 1.0 : 0.0;
        f[i++] = state.hasSlowFalling() ? 1.0 : 0.0;
        f[i++] = state.hasDolphinsGrace() ? 1.0 : 0.0;
        f[i++] = state.hasSoulSpeed() ? 1.0 : 0.0;

        var samples = state.getMovementSamples();
        if (!samples.isEmpty()) {
            double avgH = 0.0;
            double avgV = 0.0;
            double varV = 0.0;
            int groundCount = 0;
            int n = samples.size();

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

        while (i < FEATURE_COUNT) {
            f[i++] = 0.0;
        }

        return f;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
