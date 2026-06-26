package dev.ztros.ansac.physics.mlp.profile;

/**
 * 网络采样记录。
 * @param timestamp 时间戳
 * @param flyingPacketIntervalMs 飞行包间隔毫秒
 * @param packetLossRate 包丢失率 (0-1)
 * @param timerBalance 计时器余额
 */
public record NetworkSample(
    long timestamp,
    long flyingPacketIntervalMs,
    double packetLossRate,
    long timerBalance
) {}
