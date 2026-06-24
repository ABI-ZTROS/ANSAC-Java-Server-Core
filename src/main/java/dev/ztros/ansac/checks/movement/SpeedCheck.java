package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speed check - detects abnormal horizontal movement speed.
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   行走速度: 0.21585 格/刻 (4.317 m/s), 公式: 0.098 / (1 - 0.546)
 *   疾跑速度: 0.2806 格/刻 (5.612 m/s), 加速度比行走快 30%
 *   疾跑跳跃: 0.35635 格/刻 (7.127 m/s)
 *   冰面倍率: 9.27x | 蓝冰倍率: 16.85x
 *   速度药水: 基础速度 * (1 + 0.2 * 等级)
 *   灵魂疾行: 疾跑 ~0.394 + 0.03 * (等级-1) 格/刻
 *   海豚恩典: 水中速度 * 5.0
 *   潜行: 速度 * 0.3 | 使用物品: 速度 * 0.2 | 蜘蛛网: 速度 * 0.05
 *
 * Design notes:
 * - Creative/Spectator mode players are skipped entirely.
 * - Uses a buffer system: requires multiple consecutive violations to flag.
 * - Accounts for common legitimate speed sources: sprint, jump, ice, speed potion, soul speed, dolphin's grace.
 * - Knockback and teleport are handled via recent-damage and position-jump detection.
 * - Layer 2: Strafe detection - detects players maintaining abnormally consistent high speed over time.
 */
public class SpeedCheck extends Check {

    private static final double BASE_WALK = 0.21585;       // 精确值
    private static final double BASE_SPRINT = 0.2806;       // 精确值
    private static final double BASE_SPRINT_JUMP = 0.35635; // 精确值
    private static final double ICE_MULTIPLIER = 9.27;       // 冰面倍率（普通冰/浮冰/霜冰）
    private static final double BLUE_ICE_MULTIPLIER = 16.85; // 蓝冰倍率
    private static final double SOUL_SPEED_BASE = 0.406;     // 灵魂疾行基础增加量（格/刻）
    private static final double SOUL_SPEED_PER_LEVEL = 0.03; // 灵魂疾行每级额外增加
    private static final double DOLPHIN_GRACE_MULTIPLIER = 5.0; // 海豚恩典倍率
    private static final double LENIENCY = 0.05;              // 缩小容差
    private static final int BUFFER_MAX = 10;                  // 增加缓冲

    // Strafe detection thresholds
    private static final int STRAFE_HIGH_SPEED_THRESHOLD_TICKS = 40; // 2秒持续高速
    private static final double STRAFE_SPEED_RATIO = 0.9;  // 高速判定: expected * 0.9
    private static final double STRAFE_AVG_SPEED_RATIO = 0.95; // 平均速度判定: expected * 0.95

    private final ConcurrentHashMap<UUID, StrafeTracker> strafeTrackers = new ConcurrentHashMap<>();

    public SpeedCheck(ANSACPlugin plugin) {
        super(plugin, "Speed", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Skip if this looks like a teleport (large position jump)
        double teleportCheck = from.distanceSquared(to);
        if (teleportCheck > 16.0) { // 4 blocks squared
            data.setSpeedBuffer(0);
            resetStrafeTracker(player.getUniqueId());
            return;
        }

        double horizontalDist = data.getHorizontalDistance();
        if (horizontalDist < 0.05) {
            data.setSpeedBuffer(0);
            resetStrafeTracker(player.getUniqueId());
            return;
        }

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setSpeedBuffer(0);
            return;
        }

        double expected = getExpectedMaxSpeed(player);

        // Apply ping-compensated speed multiplier
        expected = data.getPingCompensator().getCompensatedSpeed(
            expected, PingCompensator.COMPENSATION_SPEED);

        // Recent damage = possible knockback, add leniency
        if (player.getNoDamageTicks() > 0) {
            expected += 0.5;
        }

        // Wind Charge / explosion knockback: exempt for 1 second after sudden velocity change
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            data.setSpeedBuffer(0);
            resetStrafeTracker(player.getUniqueId());
            return;
        }

        // Ping-compensated leniency and buffer
        double compensatedLeniency = data.getPingCompensator().getCompensatedThreshold(
            LENIENCY, PingCompensator.COMPENSATION_SPEED);
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, PingCompensator.COMPENSATION_SPEED);

        int ping = data.getPingCompensator().getAveragePing();

        // --- Layer 1: Single-tick speed check (original) ---
        if (horizontalDist > expected + compensatedLeniency) {
            int buffer = data.getSpeedBuffer() + 1;
            data.setSpeedBuffer(buffer);
            if (buffer >= compensatedBuffer) {
                double severity = horizontalDist / expected;
                flag(player, data, severity,
                    String.format("速度异常: %.3f / %.3f (连续 %d tick, 延迟 %s)",
                        horizontalDist, expected, buffer, data.getPingCompensator().getPingStatus()));
            }
        } else {
            data.setSpeedBuffer(0);
        }

        // --- Layer 2: Strafe detection (sustained high speed) ---
        checkStrafe(player, data, horizontalDist, expected);
    }

    /**
     * Strafe detection layer - detects players maintaining abnormally consistent high speed.
     * Strafe cheats adjust direction each tick to maintain constant speed, resulting in
     * very little natural variation. Normal players have speed fluctuations.
     */
    private void checkStrafe(Player player, PlayerData data, double horizontalDist, double expected) {
        UUID uuid = player.getUniqueId();
        StrafeTracker tracker = strafeTrackers.computeIfAbsent(uuid, k -> new StrafeTracker());

        tracker.tickCount++;
        tracker.totalDistance += horizontalDist;

        if (horizontalDist > expected * STRAFE_SPEED_RATIO) {
            tracker.highSpeedTicks++;
        } else {
            tracker.highSpeedTicks = 0;
            // Reset accumulated data when speed drops below threshold
            tracker.totalDistance = 0;
            tracker.tickCount = 0;
            return;
        }

        if (tracker.highSpeedTicks > STRAFE_HIGH_SPEED_THRESHOLD_TICKS) {
            double avgSpeed = tracker.totalDistance / tracker.tickCount;
            if (avgSpeed > expected * STRAFE_AVG_SPEED_RATIO) {
                double severity = avgSpeed / expected;
                flag(player, data, severity,
                    String.format("持续高速移动(Strafe检测): 平均速度=%.3f / 预期=%.3f (持续 %d tick, 延迟 %s)",
                        avgSpeed, expected, tracker.highSpeedTicks, data.getPingCompensator().getPingStatus()));
                // Reset after flagging to avoid spam
                resetStrafeTracker(uuid);
            }
        }
    }

    private void resetStrafeTracker(UUID uuid) {
        strafeTrackers.remove(uuid);
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        strafeTrackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle() || player.isGliding()
            || player.isSleeping() || player.isDead();
    }

    private double getExpectedMaxSpeed(Player player) {
        double speed = BASE_WALK;

        if (player.isSprinting()) {
            speed = player.isOnGround() ? BASE_SPRINT : BASE_SPRINT_JUMP;
        }

        // Speed potion: 基础速度 * (1 + 0.2 * level)
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            speed *= (1.0 + 0.2 * level);
        }

        // Dolphin's Grace: 正常游泳 * 5.0（仅水中生效）
        PotionEffectType dolphinsGrace = ServerVersionAdapter.getDolphinsGrace();
        if (dolphinsGrace != null && player.hasPotionEffect(dolphinsGrace) && player.isInWater()) {
            speed *= DOLPHIN_GRACE_MULTIPLIER;
        }

        // Soul Speed: speed += SOUL_SPEED_BASE + SOUL_SPEED_PER_LEVEL * (level - 1)
        PotionEffectType soulSpeed = ServerVersionAdapter.getSoulSpeed();
        if (soulSpeed != null && player.hasPotionEffect(soulSpeed)) {
            int level = player.getPotionEffect(soulSpeed).getAmplifier() + 1;
            speed += SOUL_SPEED_BASE + SOUL_SPEED_PER_LEVEL * (level - 1);
        }

        // Ice / packed ice / frosted ice: 区分普通冰和蓝冰
        if (isOnBlueIce(player)) {
            speed *= BLUE_ICE_MULTIPLIER;
        } else if (isOnIce(player)) {
            speed *= ICE_MULTIPLIER;
        }

        // Sneaking: 速度 * 0.3
        if (player.isSneaking()) {
            speed *= 0.3;
        }

        // Blocking / Using item: 速度 * 0.2
        if (player.isBlocking() || player.isHandRaised()) {
            speed *= 0.2;
        }

        // Cobweb: 速度 * 0.05
        if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
            speed *= 0.05;
        }

        return speed;
    }

    private boolean isOnIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        String type = loc.getBlock().getType().name();
        // 匹配 ICE, PACKED_ICE, FROSTED_ICE，但不匹配 BLUE_ICE
        return type.contains("ICE") && !type.contains("BLUE");
    }

    private boolean isOnBlueIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        String type = loc.getBlock().getType().name();
        return type.contains("BLUE_ICE");
    }

    /**
     * Internal tracker for Strafe detection.
     * Monitors sustained high-speed movement patterns that indicate
     * direction-adjustment cheats (Strafe).
     */
    private static class StrafeTracker {
        int highSpeedTicks;   // 连续高速 tick 数
        double totalDistance;  // 累计距离
        int tickCount;         // 总 tick 数

        StrafeTracker() {
            this.highSpeedTicks = 0;
            this.totalDistance = 0.0;
            this.tickCount = 0;
        }
    }
}
