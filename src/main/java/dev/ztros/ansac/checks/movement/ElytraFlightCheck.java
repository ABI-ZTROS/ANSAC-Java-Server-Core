package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * ElytraFlight check - detects illegal elytra flight hacks.
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   鞘翅 0度俯角水平速度: 1.5 格/刻 (30 m/s)
 *   鞘翅 30度上仰最小速度: 0.36 格/刻 (7.2 m/s)
 *   鞘翅 52度俯冲速度: 3.365 格/刻 (67.3 m/s)
 *   烟花火箭加速: 1.675 格/刻 (33.5 m/s)
 *   鞘翅水平摩擦: 0.99
 *   最佳滑翔比: ~10:1 (水平/高度)
 *
 * Detects:
 * 1. Elytra hover: player is gliding but not moving horizontally (hovering in place).
 * 2. Instant stop: player was gliding at high speed and instantly stopped without
 *    hitting a wall or the ground (legitimate elytra can only slow down gradually).
 * 3. Elytra speed hack: player is gliding faster than physics allows.
 *
 * This check ONLY runs when the player is actually gliding (isGliding() == true).
 * Normal FlyCheck skips gliding players, so this is complementary.
 */
public class ElytraFlightCheck extends Check {

    private static final int HOVER_BUFFER_MAX = 10;    // 10 ticks of hovering before flag
    private static final double HOVER_SPEED_THRESHOLD = 0.15; // 悬停阈值（最小滑翔速度 0.36，但开始/结束有过渡）
    private static final double MAX_GLIDE_SPEED = 1.5;   // 0度俯角水平速度上限（30 m/s）
    private static final double FIREWORK_MAX_SPEED = 1.675;  // 烟花火箭加速上限（33.5 m/s）
    private static final double MIN_GLIDE_SPEED = 0.36;      // 30度上仰最小速度（7.2 m/s）
    private static final double GLIDE_FRICTION = 0.99;        // 鞘翅水平摩擦
    private static final double MIN_DECEL_RATE = 0.005; // Minimum deceleration rate when not boosting
    private static final int STOP_BUFFER_MAX = 5;       // 5 ticks of instant stop before flag
    private static final double STOP_SPEED_THRESHOLD = 0.05; // Speed below this = stopped
    private static final double BOOST_DECEL_EXEMPT = 0.5; // Exempt if speed was above this (firework boost)

    public ElytraFlightCheck(ANSACPlugin plugin) {
        super(plugin, "ElytraFlight", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setElytraHoverBuffer(0);
            data.setElytraStopBuffer(0);
            data.setLastGlideSpeed(0);
            return;
        }

        // Only check when player is actually gliding
        if (!player.isGliding()) {
            // Reset buffers when not gliding
            data.setElytraHoverBuffer(0);
            data.setElytraStopBuffer(0);
            data.setLastGlideSpeed(0);
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double horizontalSpeed = data.getHorizontalDistance();
        double verticalSpeed = data.getVerticalDistance();

        // Ping-compensated thresholds
        double compensatedHoverThreshold = data.getPingCompensator().getCompensatedThreshold(
            HOVER_SPEED_THRESHOLD, PingCompensator.COMPENSATION_ELYTRA);
        int compensatedHoverBuffer = data.getPingCompensator().getCompensatedBuffer(
            HOVER_BUFFER_MAX, PingCompensator.COMPENSATION_ELYTRA);
        int compensatedStopBuffer = data.getPingCompensator().getCompensatedBuffer(
            STOP_BUFFER_MAX, PingCompensator.COMPENSATION_ELYTRA);
        double compensatedMaxSpeed = data.getPingCompensator().getCompensatedSpeed(
            MAX_GLIDE_SPEED, PingCompensator.COMPENSATION_ELYTRA);
        double compensatedFireworkSpeed = data.getPingCompensator().getCompensatedSpeed(
            FIREWORK_MAX_SPEED, PingCompensator.COMPENSATION_ELYTRA);

        // --- Check 1: Elytra hover (gliding but not moving) ---
        if (horizontalSpeed < compensatedHoverThreshold && Math.abs(verticalSpeed) < 0.05) {
            int buffer = data.getElytraHoverBuffer() + 1;
            data.setElytraHoverBuffer(buffer);
            if (buffer >= compensatedHoverBuffer) {
                flag(player, data, 1.5,
                    "鞘翅空中悬停（连续 " + buffer + " tick，水平速度: "
                    + String.format("%.3f", horizontalSpeed)
                    + "，延迟 " + data.getPingCompensator().getPingStatus() + "）");
            }
        } else {
            data.setElytraHoverBuffer(0);
        }

        // --- Check 2: Instant stop (was flying fast, suddenly stopped without collision) ---
        double lastSpeed = data.getLastGlideSpeed();
        if (lastSpeed > compensatedMaxSpeed * 0.5) { // Was moving at decent speed
            if (horizontalSpeed < STOP_SPEED_THRESHOLD) {
                // Check if player hit something (wall, ground, block)
                if (!didCollide(player)) {
                    int buffer = data.getElytraStopBuffer() + 1;
                    data.setElytraStopBuffer(buffer);
                    if (buffer >= compensatedStopBuffer) {
                        flag(player, data, 1.8,
                            "鞘翅瞬间停止（从 "
                            + String.format("%.2f", lastSpeed)
                            + " 突然降至 "
                            + String.format("%.3f", horizontalSpeed)
                            + "，未检测到碰撞，连续 " + buffer + " tick，延迟 "
                            + data.getPingCompensator().getPingStatus() + "）");
                    }
                } else {
                    data.setElytraStopBuffer(0);
                }
            } else {
                data.setElytraStopBuffer(0);
            }
        } else {
            data.setElytraStopBuffer(0);
        }

        // --- Check 3: Elytra speed hack (moving too fast horizontally) ---
        // 正常上限 1.5 格/刻（0度俯角），烟花加速上限 1.675 格/刻
        // Account for firework boost: if last speed was high, exempt for a few ticks
        boolean recentlyBoosted = lastSpeed > BOOST_DECEL_EXEMPT;
        double effectiveMax = recentlyBoosted ? compensatedFireworkSpeed : compensatedMaxSpeed;

        if (horizontalSpeed > effectiveMax) {
            double severity = horizontalSpeed / effectiveMax;
            flag(player, data, severity,
                String.format("鞘翅速度异常: %.3f 格/刻 (上限: %.2f, %s, 延迟 %s)",
                    horizontalSpeed, effectiveMax,
                    recentlyBoosted ? "烟花加速中" : "正常滑翔",
                    data.getPingCompensator().getPingStatus()));
        }

        // Update last glide speed
        data.setLastGlideSpeed(horizontalSpeed);
    }

    /**
     * Check if the player likely collided with something.
     * Checks: ground nearby, wall in movement direction, block at head level.
     */
    private boolean didCollide(Player player) {
        Location loc = player.getLocation();

        // Check if on or very near ground
        if (player.isOnGround()) return true;

        // Check ground proximity (within 1 block)
        Location below = loc.clone().subtract(0, 0.5, 0);
        if (below.getBlock().getType().isSolid()) return true;

        // Check if there's a wall in the player's movement direction
        // Use velocity direction if available, otherwise use facing direction
        Vector dir;
        if (player.getVelocity().lengthSquared() > 0.001) {
            dir = player.getVelocity().clone().normalize();
        } else {
            float yaw = loc.getYaw();
            dir = new Vector(
                -Math.sin(Math.toRadians(yaw)),
                0,
                Math.cos(Math.toRadians(yaw))
            ).normalize();
        }

        // Check 1 block ahead at feet and head level
        Location feetAhead = loc.clone().add(dir.clone().multiply(0.6));
        Location headAhead = feetAhead.clone().add(0, 1.5, 0);

        if (feetAhead.getBlock().getType().isSolid()) return true;
        if (headAhead.getBlock().getType().isSolid()) return true;

        // Check water/lava (slows down elytra)
        if (player.isInWater() || player.isInLava()) return true;

        // Check if player is in a cobweb
        if (loc.getBlock().getType().name().contains("COBWEB")) return true;

        return false;
    }
}
