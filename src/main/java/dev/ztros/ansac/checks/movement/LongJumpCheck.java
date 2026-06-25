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
 * LongJump check - detects players jumping abnormally far distances.
 *
 * 作弊原理: Meteor LongJump - 跳跃距离远超正常，利用包操作实现超远跳跃。
 * 通过修改客户端发送的位置数据，使服务器认为玩家跳了更远。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   正常行走跳跃: 约 1.3 格水平距离
 *   正常疾跑跳跃: 约 4.3 格水平距离（最远约 4.317 格）
 *   疾跑跳跃 + 跳跃提升 I: 约 5.2 格
 *   疾跑跳跃 + 跳跃提升 II: 约 6.0 格
 *   速度药水加成: 基础速度 * (1 + 0.2 * 等级)
 *   冰面跳跃: 可达 8+ 格（正常物理行为）
 *   鞘翅: 不受此检测影响（单独的 ElytraFlightCheck）
 *
 * Design notes:
 * - 跟踪完整跳跃过程：起跳 → 落地，记录起跳和落地的水平位置
 * - 正常疾跑跳跃最远约 4.3 格，加上跳跃提升最多约 6 格
 * - 水平距离 > 6.5 格（含容差）→ longJumpBuffer++
 * - longJumpBuffer >= 3 时 flag
 * - 豁免：击退、鞘翅、速度药水（动态调整阈值）、末地
 * - 使用内部类 LongJumpTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class LongJumpCheck extends Check {

    // 正常疾跑跳跃最远距离（约 4.317 格）
    private static final double NORMAL_SPRINT_JUMP = 4.317;
    // 跳跃提升每级增加的距离（约 0.9 格/级）
    private static final double JUMP_BOOST_BONUS_PER_LEVEL = 0.9;
    // 速度药水每级增加的距离（约 0.3 格/级）
    private static final double SPEED_BONUS_PER_LEVEL = 0.3;
    // 基础容差
    private static final double BASE_TOLERANCE = 0.5;
    // 起跳 deltaY 阈值（正常跳跃初速约 0.42）
    private static final double JUMP_DELTA_Y_THRESHOLD = 0.2;
    // 连续远跳次数达到此值时 flag
    private static final int LONG_JUMP_FLAG_THRESHOLD = 3;
    // 击退后豁免时间（毫秒）
    private static final long KNOCKBACK_EXEMPT_MS = 2000L;

    private final ConcurrentHashMap<UUID, LongJumpTracker> trackers = new ConcurrentHashMap<>();

    public LongJumpCheck(ANSACPlugin plugin) {
        super(plugin, "LongJump", "Movement");
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

        boolean wasOnGround = from.getBlockY() == Math.floor(from.getY())
                || from.getY() - Math.floor(from.getY()) < 0.01;
        boolean isOnGround = player.isOnGround();
        double deltaY = data.getVerticalDistance();

        UUID uuid = player.getUniqueId();
        LongJumpTracker tracker = trackers.computeIfAbsent(uuid, k -> new LongJumpTracker());

        long now = System.currentTimeMillis();

        // 跳过击退后
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            tracker.isJumping = false;
            tracker.longJumpBuffer = 0;
            return;
        }

        // 跳过鞘翅
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType().name().contains("ELYTRA")) {
            tracker.isJumping = false;
            tracker.longJumpBuffer = 0;
            return;
        }

        // 跳过鞘翅滑翔中
        if (player.isGliding()) {
            tracker.isJumping = false;
            tracker.longJumpBuffer = 0;
            return;
        }

        // 跳过末地维度
        if (player.getWorld().getEnvironment().name().contains("THE_END")) {
            tracker.isJumping = false;
            tracker.longJumpBuffer = 0;
            return;
        }

        // --- 检测起跳：从地面到空中，且 deltaY > 阈值 ---
        if (!tracker.isJumping && isOnGround && deltaY > JUMP_DELTA_Y_THRESHOLD) {
            // 玩家从地面起跳
            tracker.jumpStartX = from.getX();
            tracker.jumpStartZ = from.getZ();
            tracker.jumpStartY = from.getY();
            tracker.isJumping = true;
            return;
        }

        // --- 检测落地：从空中回到地面 ---
        if (tracker.isJumping && isOnGround) {
            // 计算水平距离
            double dx = to.getX() - tracker.jumpStartX;
            double dz = to.getZ() - tracker.jumpStartZ;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

            // 计算动态阈值（考虑跳跃提升和速度药水）
            double maxAllowed = calculateMaxJumpDistance(player);

            // Ping-compensated threshold
            maxAllowed = data.getPingCompensator().getCompensatedThreshold(
                maxAllowed, PingCompensator.COMPENSATION_SPEED);

            // Ping-compensated flag threshold
            int compensatedFlagThreshold = data.getPingCompensator().getCompensatedBuffer(
                LONG_JUMP_FLAG_THRESHOLD, PingCompensator.COMPENSATION_SPEED);

            if (horizontalDistance > maxAllowed) {
                tracker.longJumpBuffer++;

                if (tracker.longJumpBuffer >= compensatedFlagThreshold) {
                    double severity = horizontalDistance / NORMAL_SPRINT_JUMP;
                    flag(player, data, severity,
                        String.format("远跳检测: 距离=%.2f / 阈值=%.2f (连续 %d 次, 延迟 %s)",
                            horizontalDistance, maxAllowed, tracker.longJumpBuffer,
                            data.getPingCompensator().getPingStatus()));
                    // Flag 后重置 buffer
                    tracker.longJumpBuffer = 0;
                }
            } else {
                // 正常跳跃，逐渐减少 buffer
                if (tracker.longJumpBuffer > 0) {
                    tracker.longJumpBuffer--;
                }
            }

            // 重置跳跃状态
            tracker.isJumping = false;
        }
    }

    /**
     * 计算当前玩家允许的最大跳跃距离
     * 考虑跳跃提升、速度药水等效果
     */
    private double calculateMaxJumpDistance(Player player) {
        double maxDistance = NORMAL_SPRINT_JUMP + BASE_TOLERANCE;

        // 跳跃提升效果：每级增加约 0.9 格
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        if (jumpBoost != null && player.hasPotionEffect(jumpBoost)) {
            int level = player.getPotionEffect(jumpBoost).getAmplifier() + 1;
            maxDistance += JUMP_BOOST_BONUS_PER_LEVEL * level;
        }

        // 速度药水：每级增加约 0.3 格
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            maxDistance += SPEED_BONUS_PER_LEVEL * level;
        }

        return maxDistance;
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
     * Internal tracker for LongJump detection.
     * Tracks jump start position and detects abnormally long jumps.
     */
    private static class LongJumpTracker {
        double jumpStartX;       // 起跳 X 坐标
        double jumpStartZ;       // 起跳 Z 坐标
        double jumpStartY;       // 起跳 Y 坐标
        boolean isJumping;       // 是否正在跳跃过程中
        int longJumpBuffer;      // 连续远跳次数

        LongJumpTracker() {
            this.jumpStartX = 0;
            this.jumpStartZ = 0;
            this.jumpStartY = 0;
            this.isJumping = false;
            this.longJumpBuffer = 0;
        }
    }
}
