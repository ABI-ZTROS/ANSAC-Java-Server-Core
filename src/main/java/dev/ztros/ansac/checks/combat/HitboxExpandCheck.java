package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * HitboxExpand check - detects clients with expanded entity hitboxes.
 *
 * 作弊原理（基于 Meteor Client 源码分析）:
 *   Meteor Hitboxes 模块通过 Mixin 修改 Entity.getPickRadius() 返回值
 *   默认扩张 0.5，使客户端认为可以命中更远位置的实体
 *   这是纯客户端修改，不发送额外网络包
 *   但会导致客户端攻击服务端认为"边缘"的目标
 *
 * 物理参考数据（Minecraft Wiki）:
 *   玩家碰撞箱: 0.6 x 1.8 x 0.6 格
 *   末影水晶碰撞箱: 2.0 x 2.0 x 2.0 格
 *   正常攻击距离: 3.0 格（Java Edition）
 *   创造模式攻击距离: 5.0 格
 *
 * 检测策略:
 * 1. 计算攻击向量与目标中心向量的夹角
 * 2. 如果夹角过大（命中边缘），说明客户端的碰撞箱感知比服务端大
 * 3. 连续多次边缘命中则 flag
 */
public class HitboxExpandCheck extends Check {

    private static final double MAX_HIT_ANGLE = 30.0; // 最大合法命中角度（度）
    private static final int BUFFER_MAX = 8;

    public HitboxExpandCheck(ANSACPlugin plugin) {
        super(plugin, "HitboxExpand", "Combat");
    }

    /**
     * 处理攻击事件
     */
    public void processAttack(Player player, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) return;

        if (!(target instanceof LivingEntity)) return;

        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        // 计算玩家到目标中心的向量
        double toCenterX = targetLoc.getX() + target.getWidth() / 2 - (playerLoc.getX());
        double toCenterZ = targetLoc.getZ() + target.getWidth() / 2 - (playerLoc.getZ());
        double toCenterY = targetLoc.getY() + target.getHeight() / 2 - (playerLoc.getY() + player.getEyeHeight());

        // 计算玩家视线方向（使用 yaw 和 pitch）
        double yawRad = Math.toRadians(playerLoc.getYaw());
        double pitchRad = Math.toRadians(playerLoc.getPitch());

        // 视线方向向量
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        // 计算视线方向与到目标中心方向的夹角
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double centerLength = Math.sqrt(toCenterX * toCenterX + toCenterY * toCenterY + toCenterZ * toCenterZ);

        if (lookLength < 0.001 || centerLength < 0.001) return;

        double dotProduct = (lookX * toCenterX + lookY * toCenterY + lookZ * toCenterZ)
                / (lookLength * centerLength);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dotProduct))));

        // 如果夹角过大，说明玩家在"看"目标边缘
        if (angle > MAX_HIT_ANGLE) {
            int buffer = data.getHitboxExpandBuffer() + 1;
            data.setHitboxExpandBuffer(buffer);

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

            if (buffer >= compensatedBuffer) {
                double severity = angle / MAX_HIT_ANGLE;
                flag(player, data, severity,
                    String.format("碰撞箱扩张: 命中角度 %.1f° (阈值: %.0f°, 目标: %s, 连续 %d 次, 延迟 %s)",
                        angle, MAX_HIT_ANGLE, target.getName(), buffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            // 正常命中，逐渐衰减
            if (data.getHitboxExpandBuffer() > 0) {
                data.setHitboxExpandBuffer(data.getHitboxExpandBuffer() - 1);
            }
        }
    }

    @Override
    public void process(Player player, PlayerData data) {
        // No periodic processing needed, event-driven only
    }
}
