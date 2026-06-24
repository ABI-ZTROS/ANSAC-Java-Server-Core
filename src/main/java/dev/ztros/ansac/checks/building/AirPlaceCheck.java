package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPlace check - detects block placement in mid-air.
 *
 * 作弊原理（基于 Wurst/Meteor Client 源码分析）:
 *   Wurst AirPlace: 绕过 Minecraft 的方块放置支撑检测。
 *     正常 Minecraft 放置方块时，客户端会检查玩家面向的方块面是否有支撑方块。
 *     AirPlace 修改客户端的方块放置逻辑，跳过支撑检测，允许在空中放置方块。
 *     服务端收到 USE_ITEM_ON 包后，正常情况下会验证放置位置是否合法，
 *     但某些版本/配置下服务端验证不严格，AirPlace 可以成功放置。
 *   Meteor Client: 类似实现，集成在 Builder 模块中。
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   正常方块放置: 必须面向一个已有方块的表面（支撑方块）
 *   水中放置: 可以在水中放置方块（水提供临时支撑）
 *   梯子/脚手架: 可以在梯子/脚手架上放置方块
 *   末地: 末地中可以在空中放置方块（末地特殊规则）
 *   鞘翅飞行: 使用鞘翅时可能触发误报
 *
 * 检测核心:
 *   玩家不在地面、不在水中、不在梯子上时，检查放置位置下方是否有实体方块支撑。
 *   如果没有支撑方块但成功放置了方块，则标记为可疑。
 *
 * Design:
 *   - 使用 ConcurrentHashMap<UUID, AirPlaceTracker> 存储每个玩家的追踪数据
 *   - 检查玩家状态（地面/水中/梯子）
 *   - 检查放置位置下方是否有支撑方块
 *   - Buffer 系统 + PingCompensator 延迟补偿
 *   - 豁免：末地、鞘翅飞行
 */
public class AirPlaceCheck extends Check {

    // Buffer 阈值：连续空中放置次数
    private static final int BUFFER_MAX = 5;
    // 旧记录清理阈值（5秒）
    private static final long TRACKER_EXPIRE_MS = 5000L;
    // 延迟补偿因子
    private static final double COMPENSATION_FACTOR = PingCompensator.COMPENSATION_SPEED;

    // 线程安全的玩家追踪数据
    private final ConcurrentHashMap<UUID, AirPlaceTracker> trackers = new ConcurrentHashMap<>();

    public AirPlaceCheck(ANSACPlugin plugin) {
        super(plugin, "AirPlace", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // 定期清理过期数据和离线玩家的追踪记录
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        AirPlaceTracker tracker = trackers.get(uuid);
        if (tracker == null) return;

        // 如果超过 TRACKER_EXPIRE_MS 没有新的空中放置，重置 buffer
        if (tracker.lastAirPlaceTime > 0 && now - tracker.lastAirPlaceTime > TRACKER_EXPIRE_MS) {
            tracker.airPlaceBuffer = 0;
            trackers.remove(uuid);
        }
    }

    /**
     * 处理方块放置事件（由 PacketListener 调用）。
     * 检测玩家是否在空中（无支撑）放置了方块。
     *
     * @param player              放置方块的玩家
     * @param data                玩家的 PlayerData
     * @param blockPlaceLocation  方块放置的位置
     */
    public void processBlockPlace(Player player, PlayerData data, Location blockPlaceLocation) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // 跳过载具中的玩家
        if (player.isInsideVehicle()) return;

        // 跳过睡眠中的玩家
        if (player.isSleeping()) return;

        // 跳过死亡玩家
        if (player.getHealth() <= 0 || player.isDead()) return;

        // 豁免：末地环境（末地空中放置是可能的）
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) return;

        // 豁免：使用鞘翅的玩家（鞘翅飞行时可能触发误报）
        if (player.isGliding()) return;

        // Ping compensation: 延迟过高或突变时跳过检测
        if (data.getPingCompensator().shouldSkipCheck()) return;

        // 检查玩家是否在地面
        boolean onGround = player.isOnGround();

        // 检查玩家是否在水中
        Material playerBlockType = player.getLocation().getBlock().getType();
        boolean inWater = playerBlockType == Material.WATER
            || playerBlockType == Material.SEAGRASS
            || playerBlockType == Material.TALL_SEAGRASS
            || playerBlockType == Material.KELP
            || playerBlockType == Material.KELP_PLANT;

        // 检查玩家是否在梯子/脚手架上
        boolean onLadder = player.isOnLadder() || player.isClimbing();

        // 如果玩家在地面、水中或梯子上，属于正常放置
        if (onGround || inWater || onLadder) {
            // 正常放置，衰减 buffer
            UUID uuid = player.getUniqueId();
            AirPlaceTracker tracker = trackers.get(uuid);
            if (tracker != null && tracker.airPlaceBuffer > 0) {
                tracker.airPlaceBuffer--;
            }
            return;
        }

        // 玩家不在地面、不在水中、不在梯子上
        // 检查放置位置下方是否有支撑方块
        Block placedBlock = blockPlaceLocation.getBlock();
        Block belowBlock = placedBlock.getRelative(BlockFace.DOWN);

        if (isSolidBlock(belowBlock)) {
            // 下方有实体方块支撑，属于正常放置（例如放置在悬崖边缘）
            UUID uuid = player.getUniqueId();
            AirPlaceTracker tracker = trackers.get(uuid);
            if (tracker != null && tracker.airPlaceBuffer > 0) {
                tracker.airPlaceBuffer--;
            }
            return;
        }

        // 没有支撑方块，且玩家不在地面/水中/梯子上 -> 空中放置
        UUID uuid = player.getUniqueId();
        AirPlaceTracker tracker = trackers.computeIfAbsent(uuid, k -> new AirPlaceTracker());

        tracker.lastAirPlaceTime = System.currentTimeMillis();

        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, COMPENSATION_FACTOR);

        tracker.airPlaceBuffer++;
        if (tracker.airPlaceBuffer >= compensatedBuffer) {
            double severity = tracker.airPlaceBuffer / (double) BUFFER_MAX;
            flag(player, data, severity,
                String.format("空中放置: 不在地面/水中/梯子上且放置位置无支撑 (连续 %d 次, 延迟 %s)",
                    tracker.airPlaceBuffer,
                    data.getPingCompensator().getPingStatus()));
            // flag 后重置 buffer
            tracker.airPlaceBuffer = 0;
        }
    }

    /**
     * 检查方块是否为实体方块（可以提供支撑）。
     * 实体方块是指有碰撞箱的方块，如石头、泥土、木头等。
     * 非实体方块如空气、草、花、火把等不能提供支撑。
     *
     * @param block 要检查的方块
     * @return 是否为实体方块
     */
    private boolean isSolidBlock(Block block) {
        Material type = block.getType();
        if (type == Material.AIR || type.isAir()) {
            return false;
        }
        // 使用 Bukkit API 的 isSolid 判断
        // isSolid() 返回 true 表示方块有碰撞箱
        return type.isSolid();
    }

    /**
     * 内部类：存储每个玩家的空中放置追踪数据。
     */
    private static class AirPlaceTracker {
        // 违规 buffer
        int airPlaceBuffer = 0;
        // 最后空中放置时间
        long lastAirPlaceTime = 0;
    }
}
