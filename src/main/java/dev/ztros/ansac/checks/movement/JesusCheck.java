package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jesus check - detects water-walking cheats.
 *
 * 作弊原理（Wurst Jesus + Meteor Jesus）:
 *   Wurst Jesus: 在水面上方放置临时方块或修改碰撞检测，使玩家可以在水面行走。
 *   Meteor Jesus: 类似，使用 Jesus mixin 修改水面碰撞。
 *   特征：在水面/水上移动而不下沉，速度接近正常行走速度。
 *
 * 检测逻辑：
 *   1. 每 tick 检查玩家脚下方块是否为水相关方块。
 *   2. 如果脚下是水，但玩家不在水中（!player.isInWater()），且不在船上。
 *   3. 检查玩家 Y 坐标是否稳定在水面高度（不正常下沉）。
 *   4. 连续 tick 在水面行走超过阈值 → flag。
 *   5. 豁免：冰霜行者附魔、浅水（水深 < 0.5）、站在睡莲上。
 *
 * Design notes:
 *   - 使用内部类 JesusTracker + ConcurrentHashMap 保证线程安全。
 *   - 使用 PingCompensator 进行延迟补偿。
 *   - 跳过创造/旁观模式、载具中、睡眠、死亡玩家。
 */
public class JesusCheck extends Check {

    // 基础水面行走缓冲上限（tick 数），超过此值触发 flag
    private static final int BASE_JESUS_BUFFER = 20; // 20 tick = 1 秒
    // 最小水平移动阈值（低于此值不判定为行走）
    private static final double MIN_HORIZONTAL_MOVE = 0.1;
    // 水面 Y 坐标容差（玩家 Y 与水面 Y 的允许偏差）
    private static final double WATER_Y_TOLERANCE = 0.3;
    // 浅水深度阈值（低于此值视为浅水，豁免）
    private static final double SHALLOW_WATER_DEPTH = 0.5;
    // Jesus 延迟补偿因子
    private static final double COMPENSATION_JESUS = 0.20;

    // 线程安全的 tracker 存储
    private final ConcurrentHashMap<UUID, JesusTracker> trackers = new ConcurrentHashMap<>();

    public JesusCheck(ANSACPlugin plugin) {
        super(plugin, "Jesus", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式、载具中、睡眠、死亡玩家
        if (shouldSkip(player)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 延迟补偿：延迟过高或突变时跳过检测
        if (data.getPingCompensator().shouldSkipCheck()) {
            JesusTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.jesusBuffer = 0;
            }
            return;
        }

        Location to = data.getCurrentLocation();
        if (to == null) return;

        // 豁免：玩家在水中（正常游泳）
        if (player.isInWater()) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：玩家在船上
        if (player.isInsideVehicle()) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：冰霜行者附魔
        if (hasFrostWalker(player)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 获取玩家脚下的方块（精确脚底位置）
        Block feetBlock = to.getBlock();
        Block belowBlock = to.clone().subtract(0, 0.1, 0).getBlock();

        // 检查脚下是否为水相关方块
        if (!isWaterBlock(feetBlock) && !isWaterBlock(belowBlock)) {
            // 不在水面上，重置缓冲
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：站在睡莲上
        if (feetBlock.getType() == Material.LILY_PAD) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：浅水（检查下方方块，如果下方有固体方块则水深较浅）
        if (isShallowWater(to)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 检查玩家 Y 坐标是否稳定在水面高度
        // 水面 Y = 方块 Y + 1（因为水方块顶部在 Y+1）
        double waterSurfaceY = getWaterSurfaceY(feetBlock, belowBlock);
        if (waterSurfaceY < 0) {
            removeTracker(player.getUniqueId());
            return;
        }

        double playerY = to.getY();
        double yDiff = Math.abs(playerY - waterSurfaceY);

        // 如果玩家 Y 偏离水面太多（正常下沉或跳跃），不视为水面行走
        if (yDiff > WATER_Y_TOLERANCE) {
            JesusTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.jesusBuffer = 0;
            }
            return;
        }

        // 获取或创建 tracker
        JesusTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new JesusTracker(waterSurfaceY));

        // 更新水面 Y 坐标（玩家可能移动到不同水域）
        tracker.lastWaterY = waterSurfaceY;

        // 延迟补偿后的缓冲上限
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BASE_JESUS_BUFFER, COMPENSATION_JESUS);

        // 检查水平移动
        double horizontalDist = data.getHorizontalDistance();

        if (horizontalDist > MIN_HORIZONTAL_MOVE) {
            // 玩家在水面上移动
            tracker.jesusBuffer++;

            if (tracker.jesusBuffer >= compensatedBuffer) {
                double severity = tracker.jesusBuffer / (double) BASE_JESUS_BUFFER;
                flag(player, data, severity,
                    String.format("水面行走: 连续 %d tick 在水面移动 %.2f 格 (水面Y: %.1f, 玩家Y: %.1f, 延迟 %s)",
                        tracker.jesusBuffer, horizontalDist,
                        waterSurfaceY, playerY,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            // 水平移动太小，递减缓冲（玩家可能站着不动）
            if (tracker.jesusBuffer > 0) {
                tracker.jesusBuffer = Math.max(0, tracker.jesusBuffer - 1);
            }
        }
    }

    /**
     * 判断是否应跳过检测。
     */
    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isInsideVehicle()
            || player.isSleeping()
            || player.isDead();
    }

    /**
     * 检查玩家是否持有冰霜行者附魔的鞋子。
     * 冰霜行者会在水面生成冰冻方块，使玩家可以正常在水面上行走。
     */
    private boolean hasFrostWalker(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots == null) return false;
        return boots.getEnchantments().keySet().stream()
            .anyMatch(enchantment -> enchantment.getKey().getKey().equals("frost_walker"));
    }

    /**
     * 检查方块是否为水相关方块。
     * 包括 WATER、SEAGRASS、TALL_SEAGRASS 等。
     */
    private boolean isWaterBlock(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        switch (type) {
            case WATER:
            case SEAGRASS:
            case TALL_SEAGRASS:
                return true;
            default:
                // 额外检查名称包含 WATER 的方块（兼容不同版本）
                String name = type.name();
                return name.contains("WATER") || name.contains("SEAGRASS");
        }
    }

    /**
     * 检查玩家是否在浅水中。
     * 浅水定义：脚下 2 格以内有固体方块（水深 < 0.5）。
     */
    private boolean isShallowWater(Location loc) {
        Location check = loc.clone();
        // 向下检查最多 3 格，看是否有固体方块
        for (int i = 0; i < 3; i++) {
            check.subtract(0, 0.5, 0);
            Material type = check.getBlock().getType();
            if (type.isSolid() && !isWaterBlock(check.getBlock())) {
                // 找到固体方块，计算水深
                double waterDepth = loc.getY() - check.getBlock().getY() - 1.0;
                return waterDepth < SHALLOW_WATER_DEPTH;
            }
        }
        return false;
    }

    /**
     * 获取水面 Y 坐标。
     * 水方块的实际水面在其顶部（Y + 1）。
     * 返回 -1 如果无法确定水面高度。
     */
    private double getWaterSurfaceY(Block feetBlock, Block belowBlock) {
        if (isWaterBlock(feetBlock)) {
            return feetBlock.getY() + 1.0;
        }
        if (isWaterBlock(belowBlock)) {
            return belowBlock.getY() + 1.0;
        }
        return -1.0;
    }

    /**
     * 获取玩家的 JesusTracker。
     */
    private JesusTracker getTracker(UUID uuid) {
        return trackers.get(uuid);
    }

    /**
     * 移除玩家的 JesusTracker。
     */
    private void removeTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    /**
     * 内部类：Jesus 追踪器。
     * 存储每个玩家的水面行走检测状态。
     */
    private static class JesusTracker {
        /** 连续 tick 在水面行走的计数 */
        int jesusBuffer;
        /** 水面 Y 坐标 */
        double lastWaterY;

        JesusTracker(double waterSurfaceY) {
            this.jesusBuffer = 0;
            this.lastWaterY = waterSurfaceY;
        }
    }
}
