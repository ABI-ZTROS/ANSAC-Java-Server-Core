package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * NoFall check - detects players spoofing ground state to avoid fall damage.
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   摔落伤害阈值: 3.0 格高度 (超过此高度开始受伤)
 *   伤害公式: damage = fallDistance - 3
 *   每格伤害: 1 心 (2 HP)
 *   终端速度: 3.92 格/刻 (78.4 m/s)
 *   水中/岩浆中/蜘蛛网中: 无摔落伤害
 *   创造/旁观模式: 无摔落伤害
 *   鞘翅滑翔: 无摔落伤害
 *   活塞推动/风弹击退: 可能导致异常下落
 *
 * Design:
 * - 追踪玩家最后确认在地面上的 Y 坐标
 * - 当玩家声称在地面上但实际下落距离超过阈值时 flag
 * - 考虑跳跃、鞘翅、水中等合法场景
 * - PingCompensator 延迟补偿
 */
public class NoFallCheck extends Check {

    private static final double FALL_DAMAGE_THRESHOLD = 3.0; // 3格开始受伤
    private static final double GROUND_CHECK_TOLERANCE = 0.5; // 地面检测容差
    private static final int BUFFER_MAX = 5;

    public NoFallCheck(ANSACPlugin plugin) {
        super(plugin, "NoFall", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setLastGroundY(player.getLocation().getY());
            data.setNoFallBuffer(0);
            return;
        }

        // 跳过鞘翅滑翔
        if (player.isGliding()) {
            data.setNoFallBuffer(0);
            return;
        }

        // 跳过水中/岩浆中
        if (player.isInWater() || player.isInLava()) {
            data.setLastGroundY(player.getLocation().getY());
            data.setNoFallBuffer(0);
            return;
        }

        // 跳过蜘蛛网
        if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
            data.setLastGroundY(player.getLocation().getY());
            data.setNoFallBuffer(0);
            return;
        }

        // 跳过载具
        if (player.isInsideVehicle()) {
            data.setNoFallBuffer(0);
            return;
        }

        // 跳过飞行
        if (player.isFlying()) {
            data.setNoFallBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setNoFallBuffer(0);
            return;
        }

        Location to = data.getCurrentLocation();
        if (to == null) return;

        boolean onGround = player.isOnGround();

        // 如果在地面上，更新最后地面 Y
        if (onGround) {
            data.setLastGroundY(to.getY());
            data.setNoFallBuffer(0);
            return;
        }

        // 玩家声称不在地面上，检查下落距离
        double lastGroundY = data.getLastGroundY();
        if (lastGroundY == Double.MIN_VALUE) {
            data.setLastGroundY(to.getY());
            return;
        }

        double fallDistance = lastGroundY - to.getY();

        // 如果下落距离超过伤害阈值但玩家没有受伤
        if (fallDistance > FALL_DAMAGE_THRESHOLD) {
            // 检查玩家是否真的在地面上（Bukkit API 可能延迟更新）
            // 通过检查脚下是否有实体方块来验证
            boolean actuallyOnGround = isActuallyOnGround(player);

            if (actuallyOnGround && player.getNoDamageTicks() > 0) {
                // 玩家确实在地面上且刚受伤过，这是正常的
                data.setLastGroundY(to.getY());
                data.setNoFallBuffer(0);
                return;
            }

            if (actuallyOnGround && player.getFallDistance() < fallDistance - 1.0) {
                // 玩家声称在地面上，但 fallDistance 远小于实际下落距离
                // 这意味着客户端发送了虚假的 onGround 包
                int buffer = data.getNoFallBuffer() + 1;
                data.setNoFallBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_FLY);

                if (buffer >= compensatedBuffer) {
                    double severity = fallDistance / FALL_DAMAGE_THRESHOLD;
                    flag(player, data, severity,
                        String.format("NoFall: 下落 %.1f 格但未受伤 (客户端 fallDistance=%.1f, 连续 %d tick, 延迟 %s)",
                            fallDistance, player.getFallDistance(), buffer,
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                data.setNoFallBuffer(0);
            }
        } else {
            data.setNoFallBuffer(0);
        }
    }

    /**
     * 检查玩家脚下是否有实体方块
     */
    private boolean isActuallyOnGround(Player player) {
        Location loc = player.getLocation();
        // 检查脚下 0.1 格处是否有实体方块
        Location below = loc.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid();
    }
}
