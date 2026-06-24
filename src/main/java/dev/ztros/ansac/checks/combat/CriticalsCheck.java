package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Criticals check - detects fake critical hits (刀刀暴击).
 *
 * 作弊原理（基于 Wurst/Meteor Client 源码分析）:
 *   Wurst Packet 模式: 攻击前发送 4 个伪造位置包 (Y+0.0625, Y+0, Y+1.1e-5, Y+0)
 *   Meteor Packet 模式: 攻击前发送 2 个位置包 (Y+0.0625, Y+0)
 *   Mini Jump 模式: 攻击前微小跳跃 (Y速度 0.1-0.25)
 *   Full Jump 模式: 每次攻击都跳跃
 *
 * 物理参考数据（Minecraft Wiki）:
 *   暴击条件: fallDistance > 0 且 !onGround 且不在水中/岩浆
 *   暴击伤害: 基础伤害 * 1.5（向下取整）
 *   正常暴击率: 约 30-40%（跳跃攻击时）
 *   作弊暴击率: 接近 100%
 *
 * 检测策略:
 * 1. 统计攻击中的暴击率，超过阈值则 flag
 * 2. 检测攻击前微小 Y 轴位移（Packet 模式特征）
 * 3. 检测 fallDistance 与实际下落不一致
 */
public class CriticalsCheck extends Check {

    private static final double MAX_NORMAL_CRIT_RATE = 0.65; // 正常暴击率上限（65%）
    private static final int MIN_ATTACKS_SAMPLE = 5; // 最少攻击样本数
    private static final int SAMPLE_WINDOW_MS = 10000; // 10秒窗口
    private static final double MIN_FALL_FOR_CRIT = 0.1; // 暴击所需最小 fallDistance
    private static final int BUFFER_MAX = 5;

    public CriticalsCheck(ANSACPlugin plugin) {
        super(plugin, "Criticals", "Combat");
    }

    /**
     * 处理攻击事件中的暴击检测
     * 在 PlayerListener 的 EntityDamageByEntityEvent 中调用
     */
    public void processAttack(Player player, PlayerData data, EntityDamageByEntityEvent event) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) return;

        long now = System.currentTimeMillis();

        // 清理过期样本
        data.getCritAttackTimestamps().removeIf(t -> now - t > SAMPLE_WINDOW_MS);
        data.getCritSuccessTimestamps().removeIf(t -> now - t > SAMPLE_WINDOW_MS);

        // 记录此次攻击
        data.getCritAttackTimestamps().add(now);

        // 检查是否为暴击（通过伤害倍率判断）
        // 暴击伤害 = 基础伤害 * 1.5
        // 我们通过检查玩家是否在地面上 + fallDistance 来判断
        boolean isCrit = !player.isOnGround() && player.getFallDistance() > MIN_FALL_FOR_CRIT;

        // 检查是否在水中/岩浆中（水中不能暴击）
        boolean inLiquid = player.isInWater() || player.isInLava();

        if (isCrit && !inLiquid) {
            data.getCritSuccessTimestamps().add(now);

            // 额外检测：暴击时 fallDistance 很小但不在地面 = Packet 模式特征
            // 正常跳跃 fallDistance 至少 0.5+，Packet 模式通常 fallDistance < 0.2
            double fallDist = player.getFallDistance();
            if (fallDist < 0.2 && fallDist > 0) {
                // 微小 fallDistance + 不在地面 = 高度可疑的 Packet 模式
                int buffer = data.getCritPacketBuffer() + 1;
                data.setCritPacketBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

                if (buffer >= compensatedBuffer) {
                    flag(player, data, 1.5,
                        String.format("疑似 Packet 暴击: fallDistance=%.4f (不在地面, 连续 %d 次, 延迟 %s)",
                            fallDist, buffer, data.getPingCompensator().getPingStatus()));
                }
            }
        }

        // 检查暴击率
        int totalAttacks = data.getCritAttackTimestamps().size();
        int critCount = data.getCritSuccessTimestamps().size();

        if (totalAttacks >= MIN_ATTACKS_SAMPLE) {
            double critRate = (double) critCount / totalAttacks;

            if (critRate > MAX_NORMAL_CRIT_RATE) {
                int buffer = data.getCritRateBuffer() + 1;
                data.setCritRateBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

                if (buffer >= compensatedBuffer) {
                    flag(player, data, critRate,
                        String.format("暴击率异常: %.1f%% (%d/%d 次攻击, 连续 %d 次检测, 延迟 %s)",
                            critRate * 100, critCount, totalAttacks, buffer,
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                // 暴击率正常，逐渐衰减
                if (data.getCritRateBuffer() > 0) {
                    data.setCritRateBuffer(data.getCritRateBuffer() - 1);
                }
            }
        }
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup
        long now = System.currentTimeMillis();
        data.getCritAttackTimestamps().removeIf(t -> now - t > SAMPLE_WINDOW_MS);
        data.getCritSuccessTimestamps().removeIf(t -> now - t > SAMPLE_WINDOW_MS);
    }
}
