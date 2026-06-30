package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.physics.PhysicsEngine;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Speed check - detects abnormal player movement speeds.
 *
 * <p>修复说明 (2026-06-27):</p>
 * <ul>
 *   <li>复用 {@link PhysicsEngine#computeExpectedMaxHorizontalSpeed} 计算理论最大速度，
 *       避免与物理引擎重复维护同一套公式。</li>
 *   <li>药水加成从错误的 {@code +=} 修复为乘法 {@code *=}（已在 PhysicsEngine 中修正）。</li>
 *   <li>阈值从硬编码 0.462 改为基于理论速度的百分比容差（默认 10%）。</li>
 *   <li>新增对灵魂疾行 + 海豚恩惠等极端组合的兼容。</li>
 * </ul>
 */
public class SpeedCheck extends Check {

    /** 速度容差百分比：允许超出理论最大速度的百分比 */
    private static final double SPEED_TOLERANCE = 0.10;
    /** 连续异常阈值：超过容差的 tick 数达到此值才 flag */
    private static final int CONSECUTIVE_THRESHOLD = 3;

    public SpeedCheck(ANSACPlugin plugin) {
        super(plugin, "Speed", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 鞘翅飞行时跳过 Speed 检查——鞘翅速度模型由 ElytraFlightCheck 负责
        // 水中也可以用鞘翅，不能只看 isGliding
        if (player.isGliding()) return;

        // 水中/游泳时跳过——水中速度受浮力、水流、海豚恩惠等影响，波动大
        if (player.isInWater() || player.isSwimming()) return;

        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) return;

        PlayerPhysicsState state = svc.getState(player.getUniqueId());
        if (state == null) return;

        // 使用物理引擎计算当前状态下的理论最大水平速度
        double expectedMax = PhysicsEngine.computeExpectedMaxHorizontalSpeed(state);
        if (expectedMax <= 0.0) return;

        // 计算实际水平速度
        double actualSpeed = Math.sqrt(
            state.getVelocityX() * state.getVelocityX()
            + state.getVelocityZ() * state.getVelocityZ()
        );

        // 如果玩家在空中（非 Elytra 飞行），使用更宽松的判断
        // 因为空中 knockback / velocity 可以产生瞬间高速度
        boolean airborne = !state.isClientOnGround() && !state.isGliding();
        double tolerance = airborne ? SPEED_TOLERANCE * 2.0 : SPEED_TOLERANCE;

        double threshold = expectedMax * (1.0 + tolerance);

        // 检查是否超速
        if (actualSpeed > threshold) {
            data.setSpeedBuffer(data.getSpeedBuffer() + 1);

            if (data.getSpeedBuffer() >= CONSECUTIVE_THRESHOLD) {
                double severity = (actualSpeed - threshold) / expectedMax;
                flag(player, data, Math.min(severity, 5.0),
                    String.format("速度异常: %.3f / 预期 %.3f (容差 %.0f%%)",
                        actualSpeed, expectedMax, tolerance * 100));
                data.setSpeedBuffer(0);
            }
        } else {
            // 连续低于阈值时递减 buffer（避免瞬间误判累积）
            if (data.getSpeedBuffer() > 0) {
                data.setSpeedBuffer(data.getSpeedBuffer() - 1);
            }
        }
    }
}
