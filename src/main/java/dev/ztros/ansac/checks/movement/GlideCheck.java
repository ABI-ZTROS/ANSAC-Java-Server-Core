package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glide check - detects players slowing their fall speed abnormally,
 * similar to elytra gliding without actually wearing an elytra.
 *
 * 作弊原理: Wurst Glide - 减缓下落速度，类似鞘翅但无需装备。
 * 修改Y轴速度使下落速度不低于配置值（默认 > -0.25）。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   正常下落第一刻: deltaY ≈ -0.0784 (0.98 * (0 - 0.08))
 *   正常下落加速: v(t) = 0.98 * (v(t-1) - 0.08)
 *   终端速度: 3.92 格/刻
 *   缓降效果: 下落速度 * 0.125 (即每刻最多 -0.49)
 *   鞘翅滑翔: deltaY 约 -0.05 ~ -0.5，取决于俯仰角
 *
 * Design notes:
 * - 跳过创造/旁观模式、载具中、睡眠、死亡玩家
 * - 跳过水中/岩浆中、鞘翅装备、梯子/藤蔓、飘浮/缓降效果
 * - 豁免：击退后1秒、跳跃后20 tick、末地维度
 * - 使用内部类 GlideTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class GlideCheck extends Check {

    // 正常下落第一刻 deltaY ≈ -0.0784，作弊 Glide 通常使 deltaY > -0.05
    private static final double GLIDE_THRESHOLD = -0.05;
    // 持续不下落超过此 tick 数才视为可疑
    private static final int GLIDE_BUFFER_BASE = 15;
    // 跳跃后豁免 tick 数（跳跃上升约 0.7 秒 = 14 tick，留余量）
    private static final int JUMP_EXEMPT_TICKS = 20;
    // 击退后豁免时间（毫秒）
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    private final ConcurrentHashMap<UUID, GlideTracker> trackers = new ConcurrentHashMap<>();

    public GlideCheck(ANSACPlugin plugin) {
        super(plugin, "Glide", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            resetTracker(player.getUniqueId());
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // 跳过地面上的玩家
        if (player.isOnGround()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过水中/岩浆中
        if (player.isInWater() || player.isInLava()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过鞘翅装备
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType().name().contains("ELYTRA")) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过鞘翅滑翔中
        if (player.isGliding()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过梯子/藤蔓
        if (player.isClimbing()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过飘浮效果 (Levitation)
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过缓降效果 (Slow Falling)
        PotionEffectType slowFalling = getPotionEffectTypeByName("SLOW_FALLING");
        if (slowFalling != null && player.hasPotionEffect(slowFalling)) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过末地维度（末地有自然悬浮平台等特殊地形）
        if (player.getWorld().getEnvironment().name().contains("THE_END")) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过击退后
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 跳过跳跃后
        if ((now - data.getLastJumpTime()) < (JUMP_EXEMPT_TICKS * 50L)) {
            resetTracker(player.getUniqueId());
            return;
        }

        double deltaY = data.getVerticalDistance();

        // 检测：deltaY > GLIDE_THRESHOLD（几乎不下落）
        if (deltaY > GLIDE_THRESHOLD) {
            UUID uuid = player.getUniqueId();
            GlideTracker tracker = trackers.computeIfAbsent(uuid, k -> new GlideTracker());

            // Ping-compensated buffer
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                GLIDE_BUFFER_BASE, PingCompensator.COMPENSATION_FLY);

            tracker.glideBuffer++;
            tracker.lastGlideTime = now;

            if (tracker.glideBuffer >= compensatedBuffer) {
                double severity = tracker.glideBuffer / (double) GLIDE_BUFFER_BASE;
                flag(player, data, severity,
                    String.format("滑翔检测: deltaY=%.4f (持续 %d tick, 延迟 %s)",
                        deltaY, tracker.glideBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Flag 后重置，避免重复告警
                resetTracker(uuid);
            }
        } else {
            // 正常下落，重置 buffer
            resetTracker(player.getUniqueId());
        }
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }

    private void resetTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying()
            || player.isInsideVehicle()
            || player.isSleeping()
            || player.isDead();
    }

    /**
     * Get a PotionEffectType by name, returning null if not found.
     */
    private static PotionEffectType getPotionEffectTypeByName(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Internal tracker for Glide detection.
     * Tracks how long a player has been falling abnormally slowly.
     */
    private static class GlideTracker {
        int glideBuffer;         // 连续滑翔 tick 数
        long lastGlideTime;      // 上次检测到滑翔的时间

        GlideTracker() {
            this.glideBuffer = 0;
            this.lastGlideTime = 0;
        }
    }
}
