package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spider check - detects wall-climbing cheats.
 *
 * 作弊原理（Wurst Spider + Meteor Spider）:
 *   Wurst Spider: 修改玩家垂直速度模拟蜘蛛爬墙，发送向上移动包。
 *   Meteor Spider: 类似，使用 Spider mixin。
 *   特征：玩家在没有梯子/藤蔓的情况下垂直向上移动，面向墙壁。
 *
 * 检测逻辑：
 *   1. 每 tick 检查玩家垂直移动。
 *   2. 如果垂直移动 > 0.2（向上）且不在地面。
 *   3. 且不在水中、不在梯子/藤蔓上（!player.isClimbing()）。
 *   4. 且玩家面前有实体方块（根据 yaw 角计算前方方块位置）。
 *   5. 连续 tick 向上爬超过阈值 → flag。
 *   6. 豁免：跳跃提升效果、飘浮效果、被击退、使用鞘翅+烟花。
 *
 * Design notes:
 *   - 使用内部类 SpiderTracker + ConcurrentHashMap 保证线程安全。
 *   - 使用 PingCompensator 进行延迟补偿。
 *   - 跳过创造/旁观模式、载具中、睡眠、死亡玩家。
 *   - 根据玩家 yaw 角计算前方方块位置来检测是否面向墙壁。
 */
public class SpiderCheck extends Check {

    // 垂直向上移动阈值（大于此值视为可疑向上移动）
    private static final double CLIMB_VERTICAL_THRESHOLD = 0.2;
    // 基础爬墙缓冲上限（tick 数），超过此值触发 flag
    private static final int BASE_SPIDER_BUFFER = 10; // 10 tick = 500ms
    // 前方方块检测距离（格）
    private static final double WALL_CHECK_DISTANCE = 0.6;
    // Spider 延迟补偿因子
    private static final double COMPENSATION_SPIDER = 0.20;

    // 线程安全的 tracker 存储
    private final ConcurrentHashMap<UUID, SpiderTracker> trackers = new ConcurrentHashMap<>();

    public SpiderCheck(ANSACPlugin plugin) {
        super(plugin, "Spider", "Movement");
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
            SpiderTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.spiderBuffer = 0;
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();

        // 只检测向上移动
        if (deltaY <= CLIMB_VERTICAL_THRESHOLD) {
            // 没有向上移动，递减缓冲
            SpiderTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null && tracker.spiderBuffer > 0) {
                tracker.spiderBuffer = Math.max(0, tracker.spiderBuffer - 1);
            }
            return;
        }

        // 豁免：在地面（可能是跳跃）
        if (onGround) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：在水中或岩浆中
        if (player.isInWater() || player.isInLava()) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：在梯子/藤蔓上（正常攀爬）
        if (player.isClimbing()) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：跳跃提升效果
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        if (jumpBoost != null && player.hasPotionEffect(jumpBoost)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：飘浮效果
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：被击退后 1 秒内
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            SpiderTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.spiderBuffer = 0;
            }
            return;
        }

        // 豁免：鞘翅滑翔 + 烟花火箭
        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");
        if (player.isGliding() && hasElytra) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：最近跳跃（跳跃上升阶段）
        boolean recentlyJumped = (now - data.getLastJumpTime()) < 1000L;
        if (recentlyJumped) {
            SpiderTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.spiderBuffer = 0;
            }
            return;
        }

        // 检查玩家面前是否有实体方块（墙壁）
        if (!hasWallInFront(player)) {
            // 面前没有墙壁，不可能是爬墙
            SpiderTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.spiderBuffer = 0;
            }
            return;
        }

        // 获取或创建 tracker
        SpiderTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new SpiderTracker(to.getY()));

        // 延迟补偿后的缓冲上限
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BASE_SPIDER_BUFFER, COMPENSATION_SPIDER);

        // 递增爬墙缓冲
        tracker.spiderBuffer++;

        if (tracker.spiderBuffer >= compensatedBuffer) {
            double climbHeight = to.getY() - tracker.lastClimbY;
            double severity = tracker.spiderBuffer / (double) BASE_SPIDER_BUFFER;

            flag(player, data, severity,
                String.format("爬墙作弊: 连续 %d tick 向上爬 %.2f 格 (dy=%.3f, 面前有墙, 延迟 %s)",
                    tracker.spiderBuffer, climbHeight, deltaY,
                    data.getPingCompensator().getPingStatus()));

            // 更新起始 Y 坐标为当前位置，避免重复 flag
            tracker.lastClimbY = to.getY();
            tracker.spiderBuffer = 0;
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
     * 检查玩家面前是否有实体方块（墙壁）。
     * 根据玩家 yaw 角计算前方方块位置。
     * 检查脚部和头部两个高度。
     */
    private boolean hasWallInFront(Player player) {
        Location loc = player.getLocation();
        float yaw = loc.getYaw();

        // 根据 yaw 角计算前方方向向量（水平面）
        // Minecraft yaw: 0=南, 90=西, 180=北, 270=东
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);

        Vector direction = new Vector(dx, 0, dz).normalize();

        // 检查前方 WALL_CHECK_DISTANCE 格处的方块
        // 检查三个高度：脚部、腰部、头部
        for (double yOffset = 0.0; yOffset <= 1.6; yOffset += 0.8) {
            Location checkLoc = loc.clone()
                .add(direction.clone().multiply(WALL_CHECK_DISTANCE))
                .add(0, yOffset, 0);

            Block block = checkLoc.getBlock();
            if (block.getType().isSolid()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取玩家的 SpiderTracker。
     */
    private SpiderTracker getTracker(UUID uuid) {
        return trackers.get(uuid);
    }

    /**
     * 移除玩家的 SpiderTracker。
     */
    private void removeTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    /**
     * 内部类：Spider 追踪器。
     * 存储每个玩家的爬墙检测状态。
     */
    private static class SpiderTracker {
        /** 连续 tick 向上爬的计数 */
        int spiderBuffer;
        /** 开始爬的 Y 坐标（用于计算爬升高度） */
        double lastClimbY;

        SpiderTracker(double startY) {
            this.spiderBuffer = 0;
            this.lastClimbY = startY;
        }
    }
}
