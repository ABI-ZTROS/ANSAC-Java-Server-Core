package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BoatFly check - detects players flying freely while riding a boat.
 *
 * 作弊原理: Wurst BoatFly - 骑船时自由飞行，可配置前进速度和上升速度。
 * 通过修改载具的Y轴速度实现持续上升，正常情况下船不会持续上升。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   船在水中: 受浮力影响，Y轴速度约 ±0.1
 *   船在陆地上: Y轴速度约 -0.04（自然下落）
 *   气泡柱上升: Y轴速度约 0.5~1.0
 *   瀑布上升: Y轴速度约 0.3~0.5
 *   击退: 瞬间Y轴速度可达 0.3~0.5
 *
 * Design notes:
 * - 此检测不跳过载具（因为检测的就是载具飞行）
 * - 仅检测 Boat 类型载具
 * - 载具 Y 轴速度 > 0.3 且持续 > 10 tick → flag
 * - 豁免：被击退、瀑布/气泡柱上升（检查周围方块）
 * - 使用内部类 BoatFlyTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class BoatFlyCheck extends Check {

    // 船持续上升速度阈值（正常船不会持续以 > 0.3 的速度上升）
    private static final double BOAT_RISE_THRESHOLD = 0.3;
    // 持续上升超过此 tick 数才视为可疑
    private static final int BOAT_FLY_BUFFER_BASE = 10;
    // 击退后豁免时间（毫秒）
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    private final ConcurrentHashMap<UUID, BoatFlyTracker> trackers = new ConcurrentHashMap<>();

    public BoatFlyCheck(ANSACPlugin plugin) {
        super(plugin, "BoatFly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // BoatFly 不跳过载具，因为检测的就是载具中的行为
        // 但仍需跳过创造/旁观、睡眠、死亡
        if (shouldSkip(player)) return;

        // Ping compensation: skip if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 检查玩家是否在载具中
        if (!player.isInsideVehicle()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 检查载具是否是 Boat
        Vehicle vehicle = player.getVehicle();
        if (vehicle == null) {
            resetTracker(player.getUniqueId());
            return;
        }

        String vehicleType = vehicle.getType().name();
        if (!vehicleType.contains("BOAT")) {
            resetTracker(player.getUniqueId());
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();

        // 检测：载具 Y 轴速度 > 阈值（持续上升）
        if (deltaY > BOAT_RISE_THRESHOLD) {
            // 豁免：被击退后
            long now = System.currentTimeMillis();
            if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
                resetTracker(player.getUniqueId());
                return;
            }

            // 豁免：瀑布/气泡柱上升（检查周围方块）
            if (isNearBubbleColumnOrWaterfall(player)) {
                resetTracker(player.getUniqueId());
                return;
            }

            UUID uuid = player.getUniqueId();
            BoatFlyTracker tracker = trackers.computeIfAbsent(uuid, k -> new BoatFlyTracker());

            // Ping-compensated buffer
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BOAT_FLY_BUFFER_BASE, PingCompensator.COMPENSATION_FLY);

            tracker.boatFlyBuffer++;

            if (tracker.boatFlyBuffer >= compensatedBuffer) {
                double severity = tracker.boatFlyBuffer / (double) BOAT_FLY_BUFFER_BASE;
                flag(player, data, severity,
                    String.format("载具飞行检测: deltaY=%.3f (持续 %d tick, 延迟 %s)",
                        deltaY, tracker.boatFlyBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Flag 后重置
                resetTracker(uuid);
            }
        } else {
            // 正常行为，重置 buffer
            resetTracker(player.getUniqueId());
        }
    }

    /**
     * 检查玩家周围是否有气泡柱或瀑布（可导致船自然上升）
     */
    private boolean isNearBubbleColumnOrWaterfall(Player player) {
        Location loc = player.getLocation();

        // 检查玩家所在位置及下方2格
        for (int dy = 0; dy >= -2; dy--) {
            Block block = loc.clone().add(0, dy, 0).getBlock();
            Material mat = block.getType();
            String matName = mat.name();

            // 气泡柱
            if (matName.contains("BUBBLE_COLUMN")) {
                return true;
            }

            // 瀑布（流动的水源方块在上方）
            if (matName.equals("WATER") || matName.equals("SEAGRASS")
                    || matName.equals("KELP") || matName.equals("KELP_PLANT")) {
                // 检查上方是否有水（瀑布特征）
                Block above = loc.clone().add(0, dy + 1, 0).getBlock();
                if (above.getType().name().equals("WATER")) {
                    return true;
                }
            }
        }

        // 检查脚下方块是否为灵魂沙（产生向上气泡柱）
        Block below = loc.clone().subtract(0, 1, 0).getBlock();
        if (below.getType().name().equals("SOUL_SAND")) {
            // 灵魂沙上方有水会产生向上气泡柱
            if (loc.getBlock().getType().name().equals("WATER")) {
                return true;
            }
        }

        return false;
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
            || player.isSleeping()
            || player.isDead();
    }

    /**
     * Internal tracker for BoatFly detection.
     * Tracks how long a player has been rising abnormally while in a boat.
     */
    private static class BoatFlyTracker {
        int boatFlyBuffer;       // 连续异常上升 tick 数

        BoatFlyTracker() {
            this.boatFlyBuffer = 0;
        }
    }
}
