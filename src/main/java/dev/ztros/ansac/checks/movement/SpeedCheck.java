package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Speed check - detects abnormal horizontal movement speed.
 *
 * Design notes:
 * - Creative/Spectator mode players are skipped entirely.
 * - Uses a buffer system: requires multiple consecutive violations to flag.
 * - Accounts for common legitimate speed sources: sprint, jump, ice, speed potion, soul speed, dolphin's grace.
 * - Knockback and teleport are handled via recent-damage and position-jump detection.
 */
public class SpeedCheck extends Check {

    private static final double BASE_WALK = 0.215;
    private static final double BASE_SPRINT = 0.280;
    private static final double BASE_SPRINT_JUMP = 0.327;
    private static final double ICE_MULTIPLIER = 2.5;
    private static final double SOUL_SPEED_MULTIPLIER = 1.3; // per level
    private static final double LENIENCY = 0.25; // was 0.15, too strict
    private static final int BUFFER_MAX = 5; // Require 5 consecutive violations

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
            return;
        }

        double horizontalDist = data.getHorizontalDistance();
        if (horizontalDist < 0.05) {
            data.setSpeedBuffer(0);
            return;
        }

        double expected = getExpectedMaxSpeed(player);

        // Account for ping (higher ping = more leniency)
        int ping = data.getPing();
        expected *= 1.0 + (ping / 1000.0) * 0.3; // was 0.5, too generous for hackers

        // Recent damage = possible knockback, add leniency
        if (player.getNoDamageTicks() > 0) {
            expected += 0.5;
        }

        // Wind Charge / explosion knockback: exempt for 1 second after sudden velocity change
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            data.setSpeedBuffer(0);
            return;
        }

        if (horizontalDist > expected + LENIENCY) {
            int buffer = data.getSpeedBuffer() + 1;
            data.setSpeedBuffer(buffer);
            if (buffer >= BUFFER_MAX) {
                double severity = horizontalDist / expected;
                flag(player, data, severity,
                    String.format("速度异常: %.3f / %.3f (连续 %d tick, 延迟 %dms)",
                        horizontalDist, expected, buffer, ping));
            }
        } else {
            data.setSpeedBuffer(0);
        }
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

        // Speed potion
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            speed *= (1.0 + 0.2 * level);
        }

        // Dolphin's Grace (water sprinting)
        PotionEffectType dolphinsGrace = ServerVersionAdapter.getDolphinsGrace();
        if (dolphinsGrace != null && player.hasPotionEffect(dolphinsGrace)) {
            speed *= 2.5;
        }

        // Soul Speed
        PotionEffectType soulSpeed = ServerVersionAdapter.getSoulSpeed();
        if (soulSpeed != null && player.hasPotionEffect(soulSpeed)) {
            int level = player.getPotionEffect(soulSpeed).getAmplifier() + 1;
            speed *= (1.0 + SOUL_SPEED_MULTIPLIER * level);
        }

        // Ice / packed ice / blue ice
        if (isOnIce(player)) {
            speed *= ICE_MULTIPLIER;
        }

        // Sneaking
        if (player.isSneaking()) {
            speed *= 0.3;
        }

        // Blocking
        if (player.isBlocking()) {
            speed *= 0.2;
        }

        // Cobweb
        if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
            speed *= 0.05;
        }

        return speed;
    }

    private boolean isOnIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        String type = loc.getBlock().getType().name();
        return type.contains("ICE");
    }
}
