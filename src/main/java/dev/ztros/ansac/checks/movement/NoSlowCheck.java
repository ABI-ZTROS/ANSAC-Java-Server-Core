package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * NoSlow check - detects players moving too fast while using items.
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   使用物品速度倍率: 0.2 (基础速度的 20%)
 *   拉弓速度倍率: 0.2
 *   盾牌格挡速度倍率: 0.2
 *   潜行速度倍率: 0.3
 *   蜘蛛网速度倍率: 0.05
 *
 * Design:
 * - 仅在玩家确实处于使用物品状态时检测
 * - 考虑冰面、速度药水等加成
 * - PingCompensator 延迟补偿
 * - Buffer 系统避免单 tick 误报
 */
public class NoSlowCheck extends Check {

    private static final double USE_ITEM_SPEED_MULTIPLIER = 0.2;
    private static final double BASE_WALK = 0.21585;
    private static final double BASE_SPRINT = 0.2806;
    private static final double ICE_MULTIPLIER = 9.27;
    private static final double BLUE_ICE_MULTIPLIER = 16.85;
    private static final double LENIENCY = 0.03;
    private static final int BUFFER_MAX = 8;

    public NoSlowCheck(ANSACPlugin plugin) {
        super(plugin, "NoSlow", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 仅在玩家使用物品或格挡时检测
        if (!player.isHandRaised() && !player.isBlocking()) {
            data.setNoSlowBuffer(0);
            return;
        }

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setNoSlowBuffer(0);
            return;
        }

        // 跳过水中/岩浆中（游泳不受 NoSlow 影响）
        if (player.isInWater() || player.isInLava()) {
            data.setNoSlowBuffer(0);
            return;
        }

        // 跳过载具
        if (player.isInsideVehicle()) {
            data.setNoSlowBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setNoSlowBuffer(0);
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double horizontalDist = data.getHorizontalDistance();
        if (horizontalDist < 0.01) {
            data.setNoSlowBuffer(0);
            return;
        }

        // 计算使用物品时的最大允许速度
        double baseSpeed = player.isSprinting() ? BASE_SPRINT : BASE_WALK;
        double maxSpeed = baseSpeed * USE_ITEM_SPEED_MULTIPLIER;

        // 速度药水加成
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed *= (1.0 + 0.2 * level);
        }

        // 冰面加成
        Location below = player.getLocation().clone().subtract(0, 1, 0);
        String blockType = below.getBlock().getType().name();
        if (blockType.contains("BLUE_ICE")) {
            maxSpeed *= BLUE_ICE_MULTIPLIER;
        } else if (blockType.contains("ICE")) {
            maxSpeed *= ICE_MULTIPLIER;
        }

        // 蜘蛛网
        if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
            maxSpeed *= 0.05;
        }

        // Ping 补偿
        maxSpeed = data.getPingCompensator().getCompensatedSpeed(maxSpeed, PingCompensator.COMPENSATION_SPEED);
        double compensatedLeniency = data.getPingCompensator().getCompensatedThreshold(LENIENCY, PingCompensator.COMPENSATION_SPEED);
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(BUFFER_MAX, PingCompensator.COMPENSATION_SPEED);

        if (horizontalDist > maxSpeed + compensatedLeniency) {
            int buffer = data.getNoSlowBuffer() + 1;
            data.setNoSlowBuffer(buffer);
            if (buffer >= compensatedBuffer) {
                double severity = horizontalDist / maxSpeed;
                flag(player, data, severity,
                    String.format("使用物品时移动过快: %.3f / %.3f (连续 %d tick, 延迟 %s)",
                        horizontalDist, maxSpeed, buffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            data.setNoSlowBuffer(0);
        }
    }
}
