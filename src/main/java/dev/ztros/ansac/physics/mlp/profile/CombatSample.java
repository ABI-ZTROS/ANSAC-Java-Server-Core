package dev.ztros.ansac.physics.mlp.profile;

/**
 * 战斗采样记录。
 * @param timestamp 时间戳
 * @param cps 每秒点击数
 * @param attackIntervalMs 攻击间隔毫秒
 * @param isCritical 是否为暴击
 * @param reachDistance 攻击距离
 * @param yawDelta 偏航角变化
 * @param pitchDelta 俯仰角变化
 */
public record CombatSample(
    long timestamp,
    double cps,
    long attackIntervalMs,
    boolean isCritical,
    double reachDistance,
    float yawDelta,
    float pitchDelta
) {}
