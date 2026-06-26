package dev.ztros.ansac.physics.mlp.profile;

/**
 * 建造采样记录。
 * @param timestamp 时间戳
 * @param placeIntervalMs 放置间隔毫秒
 * @param breakIntervalMs 破坏间隔毫秒
 * @param directionConsistency 放置方向一致性 (-1~1)
 * @param isAirPlace 是否空中放置
 * @param blocksPlaced 本次放置方块数
 */
public record BuildingSample(
    long timestamp,
    long placeIntervalMs,
    long breakIntervalMs,
    double directionConsistency,
    boolean isAirPlace,
    int blocksPlaced
) {}
