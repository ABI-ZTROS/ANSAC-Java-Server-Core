package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Velocity check - detects players reducing or eliminating knockback (AntiKB).
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   正常击退水平速度: ~0.4 格/刻 (受攻击者)
 *   正常击退垂直速度: ~0.4 格/刻 (向上)
 *   击退衰减: 每刻乘 0.8
 *   击退持续时间: ~20 tick (1秒)
 *   连击击退累加: 每次攻击增加击退
 *
 * Design:
 * - 记录玩家受到伤害前的位置和速度
 * - 受伤后检查位移是否符合击退预期
 * - 考虑风弹/爆炸等特殊击退（通过 lastKnockbackTime 豁免）
 * - PingCompensator 延迟补偿
 * - Buffer 系统避免误报
 */
public class VelocityCheck extends Check {

    private static final double MIN_KNOCKBACK_SPEED = 0.15; // 最小合法击退速度（格/刻）
    private static final double MIN_KNOCKBACK_DISTANCE = 0.3; // 最小合法击退位移（格）
    private static final int CHECK_TICKS = 5; // 受伤后检查 5 tick
    private static final int BUFFER_MAX = 5;

    public VelocityCheck(ANSACPlugin plugin) {
        super(plugin, "Velocity", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setVelocityBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setVelocityBuffer(0);
            return;
        }

        long now = System.currentTimeMillis();
        long lastDamage = data.getLastDamageTime();

        // 如果最近没有受到伤害，重置
        if (lastDamage == 0 || now - lastDamage > CHECK_TICKS * 50L) {
            data.setVelocityBuffer(0);
            return;
        }

        // 跳过风弹/爆炸击退（这些有特殊处理）
        if (now - data.getLastKnockbackTime() < 1000L) {
            data.setVelocityBuffer(0);
            return;
        }

        // 检查受伤后玩家是否几乎没有移动
        Vector velocity = player.getVelocity();
        double speed = velocity.length();

        // 如果玩家正在受到击退（速度大于阈值），这是正常的
        if (speed > MIN_KNOCKBACK_SPEED) {
            data.setVelocityBuffer(0);
            return;
        }

        // 玩家受伤后速度几乎为零 = 可疑
        // 但需要确认玩家确实受到了攻击伤害（不是摔落/火焰等）
        if (data.isLastDamageAttack()) {
            int buffer = data.getVelocityBuffer() + 1;
            data.setVelocityBuffer(buffer);

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

            if (buffer >= compensatedBuffer) {
                double severity = buffer / (double) BUFFER_MAX;
                flag(player, data, severity,
                    String.format("击退异常: 受到攻击后速度 %.3f (阈值: %.2f, 连续 %d tick, 延迟 %s)",
                        speed, MIN_KNOCKBACK_SPEED, buffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            data.setVelocityBuffer(0);
        }
    }
}
