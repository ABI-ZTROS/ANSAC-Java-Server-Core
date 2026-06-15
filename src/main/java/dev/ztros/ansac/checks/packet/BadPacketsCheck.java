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
    private static final double MAX_YAW_RATE = 180.0; // Max yaw change per tick
    private static final double MAX_PITCH_RATE = 90.0; // Max pitch change per tick

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
                String.format("Invalid pitch: %.1f (max: %.1f)", pitch, MAX_PITCH));
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

        // Normalize yaw delta
        if (yawDelta > 180) {
            yawDelta = 360 - yawDelta;
        }

        // Check for impossible rotation speed
        if (yawDelta > MAX_YAW_RATE) {
            flag(player, data, yawDelta / MAX_YAW_RATE,
                String.format("Impossible yaw change: %.1f deg/tick (max: %.1f)", yawDelta, MAX_YAW_RATE));
        }

        if (pitchDelta > MAX_PITCH_RATE) {
            flag(player, data, pitchDelta / MAX_PITCH_RATE,
                String.format("Impossible pitch change: %.1f deg/tick (max: %.1f)", pitchDelta, MAX_PITCH_RATE));
        }

        // Check for invalid pitch values
        if (Math.abs(newPitch) > MAX_PITCH) {
            flag(player, data, 2.0,
                String.format("Invalid pitch packet: %.1f (max: %.1f)", newPitch, MAX_PITCH));
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
                String.format("Invalid position: NaN/Inf (%.2f, %.2f, %.2f)", x, y, z));
        }

        // Check for extreme coordinates (possible crash attempt)
        double MAX_COORD = 30000000;
        if (Math.abs(x) > MAX_COORD || Math.abs(y) > MAX_COORD || Math.abs(z) > MAX_COORD) {
            flag(player, data, 3.0,
                String.format("Extreme coordinates: (%.2f, %.2f, %.2f)", x, y, z));
        }
    }
}
