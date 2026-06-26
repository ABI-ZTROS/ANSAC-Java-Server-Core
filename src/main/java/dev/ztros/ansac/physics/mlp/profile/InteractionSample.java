package dev.ztros.ansac.physics.mlp.profile;

/**
 * 交互采样记录。
 * @param timestamp 时间戳
 * @param eatDurationTicks 吃东西持续tick
 * @param blockDurationTicks 格挡持续tick
 * @param useItemIntervalMs 使用物品间隔毫秒
 * @param isFastUse 是否快速使用
 */
public record InteractionSample(
    long timestamp,
    int eatDurationTicks,
    int blockDurationTicks,
    long useItemIntervalMs,
    boolean isFastUse
) {}
