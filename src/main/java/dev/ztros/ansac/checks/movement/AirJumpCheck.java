package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirJump check - detects players jumping while already airborne (double-jump cheat).
 *
 * Cheat principle (Meteor AirJump):
 *   Meteor AirJump: Allows the player to jump again while in mid-air (similar to double-jump).
 *   Normal Minecraft: Players can only jump when on the ground (onGround = true).
 *   Cheat signature: Player gains upward velocity of ~0.42 while not on the ground.
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Normal jump initial velocity: 0.42 blocks/tick (Y-axis)
 *   Gravity formula: v(t) = 0.98 * (v(t-1) - 0.08)
 *   Jump Boost: adds +0.1 per level to initial velocity
 *   Levitation: overrides gravity with upward velocity of 0.05 * (amplifier + 1)
 *
 * Detection logic:
 *   - Tracks whether the player was on the ground in the previous tick.
 *   - If the player was NOT on ground (wasOnGround = false) and suddenly gains
 *     upward velocity > 0.35 (close to jump initial speed of 0.42), this is suspicious.
 *   - Exemptions: water, ladders/vines, Jump Boost, Levitation, knockback, elytra + firework.
 *   - Uses a buffer system to avoid false positives from single-tick anomalies.
 *   - Uses PingCompensator for latency compensation.
 */
public class AirJumpCheck extends Check {

    // Detection thresholds
    private static final double MIN_JUMP_DELTA_Y = 0.35; // Close to normal jump initial velocity (0.42)
    private static final long MIN_TIME_SINCE_JUMP_MS = 500L; // Exclude the first frame of a normal jump
    private static final long KNOCKBACK_EXEMPT_MS = 1000L; // Exempt for 1 second after knockback
    private static final int BUFFER_FLAG_THRESHOLD = 3; // Require 3 violations before flagging

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.20;

    /**
     * Internal tracker for per-player air jump state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AirJumpTracker {
        boolean wasOnGround;
        int airJumpBuffer;
        long lastAirJumpTime;

        AirJumpTracker() {
            this.wasOnGround = true;
            this.airJumpBuffer = 0;
            this.lastAirJumpTime = 0;
        }

        void reset() {
            this.wasOnGround = true;
            this.airJumpBuffer = 0;
            this.lastAirJumpTime = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AirJumpTracker> trackers = new ConcurrentHashMap<>();

    public AirJumpCheck(ANSACPlugin plugin) {
        super(plugin, "AirJump", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AirJumpTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.airJumpBuffer = 0;
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Skip teleport-like movements
        if (from.distanceSquared(to) > 16.0) {
            cleanupTracker(player.getUniqueId());
            return;
        }

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        AirJumpTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AirJumpTracker());

        // Exemption checks
        if (shouldExempt(player, data, now)) {
            tracker.airJumpBuffer = 0;
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Core detection ---
        // Condition: player was NOT on ground last tick, but now gains significant upward velocity
        if (!tracker.wasOnGround && deltaY > MIN_JUMP_DELTA_Y) {
            // Exclude the first frame of a normal jump (player just left ground)
            long timeSinceLastJump = now - data.getLastJumpTime();
            if (timeSinceLastJump > MIN_TIME_SINCE_JUMP_MS) {
                // Exclude recent knockback
                long timeSinceKnockback = now - data.getLastKnockbackTime();
                if (timeSinceKnockback > KNOCKBACK_EXEMPT_MS) {
                    tracker.airJumpBuffer++;
                    tracker.lastAirJumpTime = now;

                    int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                        BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                    if (tracker.airJumpBuffer >= compensatedBuffer) {
                        double severity = deltaY / MIN_JUMP_DELTA_Y;
                        flag(player, data, severity,
                            String.format("空中跳跃: dy=%.3f (连续 %d 次, 距上次跳跃 %dms, 延迟 %s)",
                                deltaY, tracker.airJumpBuffer, timeSinceLastJump,
                                data.getPingCompensator().getPingStatus()));
                    }
                } else {
                    // Recent knockback, decay buffer
                    tracker.airJumpBuffer = Math.max(0, tracker.airJumpBuffer - 1);
                }
            }
            // else: this is the first frame of a normal jump, ignore
        } else {
            // Gradually decay buffer on legitimate movement
            if (tracker.airJumpBuffer > 0 && deltaY <= 0) {
                tracker.airJumpBuffer = Math.max(0, tracker.airJumpBuffer - 1);
            }
        }

        // Update ground state
        tracker.wasOnGround = onGround;
    }

    /**
     * Check if the player should be exempted from this check.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // In water or lava (different physics, player can "swim" upward)
        if (player.isInWater() || player.isInLava()) {
            return true;
        }

        // Climbing ladders or vines (can gain upward velocity)
        if (player.isClimbing()) {
            return true;
        }

        // Jump Boost effect (increases jump velocity, could trigger false positive)
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        if (jumpBoost != null && player.hasPotionEffect(jumpBoost)) {
            return true;
        }

        // Levitation effect (overrides gravity, player floats upward)
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            return true;
        }

        // Elytra + firework rocket (gives upward boost in mid-air)
        if (player.isGliding()) {
            ItemStack chestplate = player.getInventory().getChestplate();
            if (chestplate != null && chestplate.getType().name().contains("ELYTRA")) {
                return true;
            }
        }

        // Slow Falling effect (might affect vertical movement patterns)
        PotionEffectType slowFalling = getPotionEffectType("SLOW_FALLING");
        if (slowFalling != null && player.hasPotionEffect(slowFalling)) {
            return true;
        }

        return false;
    }

    /**
     * Safely get a PotionEffectType by name via reflection.
     * Used for effects not yet in ServerVersionAdapter.
     */
    private PotionEffectType getPotionEffectType(String name) {
        try {
            java.lang.reflect.Field field = PotionEffectType.class.getField(name);
            return (PotionEffectType) field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if the player should be skipped entirely (non-cheat reasons).
     */
    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }

    /**
     * Clean up tracker when player disconnects or teleports.
     */
    private void cleanupTracker(UUID uuid) {
        trackers.remove(uuid);
    }
}
