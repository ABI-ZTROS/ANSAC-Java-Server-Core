package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Fly check - detects abnormal vertical movement and flight.
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   跳跃初速: 0.42 格/刻 (Y轴)
 *   重力公式: v(t) = 0.98 * (v(t-1) - 0.08), 即每刻先减 0.08 再乘 0.98
 *   终端速度: 3.92 格/刻 (78.4 m/s), 实际约 3.709 格/刻
 *   跳跃提升: 初速 + 0.1 * 等级
 *   风弹击退: ~6格高度, 2.5格水平
 *
 * Design notes:
 * - Creative/Spectator mode players are skipped entirely.
 * - Players in vehicles, sleeping, or dead are skipped.
 * - Normal jumping, falling, climbing, swimming, and elytra gliding are exempted.
 * - Uses a buffer system to avoid false positives from single-tick anomalies.
 * - Ground proximity check prevents edge-standing false positives.
 */
public class FlyCheck extends Check {

    private static final double LENIENCY = 0.15;
    private static final int BUFFER_MAX = 8; // Was 6, increase for more leniency
    private static final double JUMP_INITIAL_VELOCITY = 0.42; // Normal jump initial dy
    private static final int JUMP_EXEMPT_TICKS = 20;          // 增加到 20 tick（跳跃上升约 0.7 秒）
    private static final double GRAVITY_INITIAL = 0.08;        // 初始重力减速
    private static final double GRAVITY_MULTIPLIER = 0.98;     // 重力乘数
    private static final double TERMINAL_VELOCITY = 3.92;      // 终端速度

    public FlyCheck(ANSACPlugin plugin) {
        super(plugin, "Fly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        // --- Jump tracking ---
        // If player just jumped (large positive deltaY), mark them as jumping
        if (onGround && deltaY > 0.3) {
            data.setLastJumpTime(now);
        }
        boolean recentlyJumped = (now - data.getLastJumpTime()) < (JUMP_EXEMPT_TICKS * 50L);

        // --- Wind Charge / explosion knockback detection ---
        // Wind charge gives a sudden velocity boost; exempt for a short time
        boolean recentKnockback = (now - data.getLastKnockbackTime()) < 1000L; // 1 second

        // --- Elytra / Firework rocket check ---
        // Player might be using firework with elytra (gives upward boost)
        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");
        boolean usingFirework = player.isGliding() || hasElytra && deltaY > 0.3;

        // --- Ground proximity check ---
        // If player is near ground, they might be edge-standing or about to land
        double distToGround = distanceToGround(player);
        boolean nearGround = distToGround >= 0 && distToGround < 1.5;

        // --- Check 1: sustained hover ---
        // Not on ground, not moving vertically, not in liquid, not climbing, not near ground
        if (!onGround && Math.abs(deltaY) < 0.001
                && !player.isInWater() && !player.isInLava()
                && !player.isClimbing()
                && !nearGround
                && !recentlyJumped
                && !usingFirework
                && !recentKnockback) {
            int hoverBuffer = data.getHoverBuffer() + 1;
            data.setHoverBuffer(hoverBuffer);
            if (hoverBuffer >= BUFFER_MAX) {
                flag(player, data, 1.5, "空中悬停（连续 " + hoverBuffer + " tick）");
            }
            return;
        } else {
            data.setHoverBuffer(0);
        }

        // --- Check 2: ascending while not on ground ---
        // Skip if recently jumped, has jump boost, levitation, climbing, in liquid, near ground, using firework, or recent knockback
        if (!onGround && deltaY > LENIENCY) {
            if (recentlyJumped || usingFirework || nearGround || recentKnockback) {
                data.setAscendBuffer(0);
            } else {
                PotionEffectType levitation = ServerVersionAdapter.getLevitation();
                boolean hasLevitation = levitation != null && player.hasPotionEffect(levitation);
                PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
                boolean hasJumpBoost = jumpBoost != null && player.hasPotionEffect(jumpBoost);

                if (!hasLevitation && !hasJumpBoost && !player.isClimbing()
                        && !player.isInWater() && !player.isInLava()) {
                    int ascendBuffer = data.getAscendBuffer() + 1;
                    data.setAscendBuffer(ascendBuffer);
                    if (ascendBuffer >= BUFFER_MAX) {
                        flag(player, data, deltaY / LENIENCY,
                            String.format("空中异常上升: dy=%.3f (连续 %d tick)", deltaY, ascendBuffer));
                    }
                    return;
                } else {
                    data.setAscendBuffer(0);
                }
            }
        } else {
            data.setAscendBuffer(0);
        }

        // --- Check 3: falling too slowly ---
        // 正常下落第一刻: deltaY ≈ -0.08 * 0.98 = -0.0784
        // 之后加速到 -3.92（终端速度）
        // 如果 deltaY > -0.05（绝对值太小），持续多 tick 才可疑
        if (!onGround && deltaY < -LENIENCY) {
            if (deltaY > -0.05 && !player.isInWater() && !player.isInLava()
                    && !player.isClimbing() && !recentlyJumped && !usingFirework && !recentKnockback) {
                int fallBuffer = data.getFallBuffer() + 1;
                data.setFallBuffer(fallBuffer);
                if (fallBuffer >= BUFFER_MAX) {
                    flag(player, data, 1.2,
                        String.format("下落过慢: dy=%.3f (连续 %d tick)", deltaY, fallBuffer));
                }
                return;
            } else {
                data.setFallBuffer(0);
            }
        } else {
            data.setFallBuffer(0);
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }

    /**
     * Calculate distance from player's feet to the nearest solid block below.
     * Returns -1 if no ground found within reasonable distance.
     */
    private double distanceToGround(Player player) {
        Location loc = player.getLocation().clone();
        double startY = loc.getY();
        // Check up to 5 blocks down
        for (int i = 0; i < 10; i++) {
            loc.subtract(0, 0.5, 0);
            if (loc.getBlock().getType().isSolid()) {
                return startY - loc.getY();
            }
        }
        return -1;
    }
}
