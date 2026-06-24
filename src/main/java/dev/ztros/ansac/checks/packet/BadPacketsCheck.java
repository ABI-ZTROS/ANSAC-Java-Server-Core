package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * BadPackets check - detects invalid or malformed packets.
 * Reference: GrimAC's packet validation approach.
 */
public class BadPacketsCheck extends Check {

    private static final double MAX_PITCH = 90.0;
    private static final double MAX_YAW_RATE = 360.0; // Was 180, too strict for fast mouse movement
    private static final double MAX_PITCH_RATE = 180.0; // Was 90, too strict
    private static final int ROTATION_BUFFER_MAX = 3; // Require 3 consecutive violations

    public BadPacketsCheck(ANSACPlugin plugin) {
        super(plugin, "BadPackets", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // This check is primarily event-driven via packet listener
        // Periodic check validates player state consistency

        // Check for impossible rotation values
        float pitch = player.getLocation().getPitch();
        if (Math.abs(pitch) > MAX_PITCH + 1) {
            flag(player, data, 2.0,
                String.format("视角俯仰角异常: %.1f (上限: %.1f)", pitch, MAX_PITCH));
        }
    }

    /**
     * Validate rotation change (called from packet listener)
     */
    public void validateRotation(Player player, PlayerData data, float newYaw, float newPitch, float lastYaw, float lastPitch) {
        if (!isEnabled() || data.hasBypass()) return;

        // Calculate rotation deltas
        float yawDelta = Math.abs(newYaw - lastYaw);
        float pitchDelta = Math.abs(newPitch - lastPitch);

        // Normalize yaw delta (yaw wraps around at 360)
        if (yawDelta > 180) {
            yawDelta = 360 - yawDelta;
        }

        // Check for impossible rotation speed (with buffer)
        if (yawDelta > MAX_YAW_RATE) {
            int buffer = data.getRotationBuffer() + 1;
            data.setRotationBuffer(buffer);
            if (buffer >= ROTATION_BUFFER_MAX) {
                flag(player, data, yawDelta / MAX_YAW_RATE,
                    String.format("视角偏航变化异常: %.1f 度/刻 (上限: %.1f, 连续 %d 次)", yawDelta, MAX_YAW_RATE, buffer));
            }
        } else {
            data.setRotationBuffer(0);
        }

        if (pitchDelta > MAX_PITCH_RATE) {
            int buffer = data.getRotationBuffer() + 1;
            data.setRotationBuffer(buffer);
            if (buffer >= ROTATION_BUFFER_MAX) {
                flag(player, data, pitchDelta / MAX_PITCH_RATE,
                    String.format("视角俯仰变化异常: %.1f 度/刻 (上限: %.1f, 连续 %d 次)", pitchDelta, MAX_PITCH_RATE, buffer));
            }
        } else {
            data.setRotationBuffer(0);
        }

        // Check for invalid pitch values
        if (Math.abs(newPitch) > MAX_PITCH) {
            flag(player, data, 2.0,
                String.format("视角俯仰角无效: %.1f (上限: %.1f)", newPitch, MAX_PITCH));
        }
    }

    /**
     * Validate position packet (called from packet listener)
     */
    public void validatePosition(Player player, PlayerData data, double x, double y, double z) {
        if (!isEnabled() || data.hasBypass()) return;

        // Check for NaN or Infinite values
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) ||
            Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
            flag(player, data, 3.0,
                String.format("坐标无效: NaN/Inf (%.2f, %.2f, %.2f)", x, y, z));
        }

        // Check for extreme coordinates (possible crash attempt)
        double MAX_COORD = 30000000;
        if (Math.abs(x) > MAX_COORD || Math.abs(y) > MAX_COORD || Math.abs(z) > MAX_COORD) {
            flag(player, data, 3.0,
                String.format("坐标超出范围: (%.2f, %.2f, %.2f)", x, y, z));
        }
    }
}
