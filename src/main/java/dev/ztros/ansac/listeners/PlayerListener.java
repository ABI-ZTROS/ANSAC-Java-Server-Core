package dev.ztros.ansac.listeners;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.checks.CheckManager;
import dev.ztros.ansac.physics.PhysicsConstants;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.checks.combat.AutoArmorCheck;
import dev.ztros.ansac.checks.combat.AutoLogCheck;
import dev.ztros.ansac.checks.combat.AutoTotemCheck;
import dev.ztros.ansac.checks.combat.BowAimbotCheck;
import dev.ztros.ansac.checks.combat.BowSpamCheck;
import dev.ztros.ansac.checks.combat.CrystalAuraCheck;
import dev.ztros.ansac.checks.combat.CriticalsCheck;
import dev.ztros.ansac.checks.combat.HitboxExpandCheck;
import dev.ztros.ansac.checks.combat.QuiverCheck;
import dev.ztros.ansac.checks.player.AutoEatCheck;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

/**
 * Bukkit event listener for player-related events.
 * Uses Folia-safe event handling.
 */
public class PlayerListener implements Listener {

    private final ANSACPlugin plugin;

    public PlayerListener(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        var entry = plugin.getPunishmentManager().getActiveBan(event.getPlayer().getUniqueId());
        if (entry != null) {
            net.kyori.adventure.text.Component message = plugin.getPunishmentManager().getBanScreen(entry);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, message);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getSchedulerAdapter().runNextTick(() -> {
            plugin.getPlayerDataManager().createPlayerData(event.getPlayer());
            plugin.getLogger().info("开始追踪玩家：" + event.getPlayer().getName());

            // 开始录制 Demo
            if (plugin.getDemoRecorderManager() != null) {
                plugin.getDemoRecorderManager().startRecording(event.getPlayer());
            }

            // Notify auth service
            if (plugin.getAuthService().isEnabled()) {
                String ip = "unknown";
                try {
                    if (event.getPlayer().getAddress() != null
                            && event.getPlayer().getAddress().getAddress() != null) {
                        ip = event.getPlayer().getAddress().getAddress().getHostAddress();
                    }
                } catch (Exception ignored) {}
                plugin.getAuthService().handlePlayerJoin(
                    event.getPlayer().getUniqueId(),
                    event.getPlayer().getName(),
                    ip
                );
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getSchedulerAdapter().runNextTick(() -> {
            // Notify all checks to clean up per-player state before removing PlayerData
            plugin.getCheckManager().onPlayerQuit(event.getPlayer().getUniqueId());
            // Clean up physics inference state
            if (plugin.getPhysicsInferenceService() != null) {
                plugin.getPhysicsInferenceService().onPlayerQuit(event.getPlayer().getUniqueId());
            }
            // 保存并停止 Demo 录制
            if (plugin.getDemoRecorderManager() != null) {
                plugin.getDemoRecorderManager().stopAndSave(event.getPlayer().getUniqueId());
            }
            plugin.getPlayerDataManager().removePlayerData(event.getPlayer());

            // Notify auth service
            if (plugin.getAuthService().isEnabled()) {
                plugin.getAuthService().handlePlayerQuit(event.getPlayer().getUniqueId());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        // Only process if actually moved
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(event.getPlayer());

        // 注意：即使 data 为 null 也不 return，
        // 推理服务需要收到移动数据才能建立物理状态。
        // PlayerData 在 onPlayerJoin 中通过 runNextTick 延迟创建，
        // 在 Folia 多线程下可能存在 PlayerData 尚未创建但玩家已开始移动的时间窗口。
        // 推理服务内部已处理 playerData == null 的情况。

        // Update ping sample for latency compensation
        if (data != null) {
            data.getPingCompensator().addPingSample(data.getPing());
        }

        // Detect sudden velocity changes (wind charge, explosion knockback, etc.)
        PhysicsInferenceService inferenceService = plugin.getPhysicsInferenceService();
        Vector velocity = event.getPlayer().getVelocity();
        double velLen = velocity.length();
        if (velLen > PhysicsConstants.MIN_KNOCKBACK_SPEED) {
            long now = System.currentTimeMillis();
            if (data != null) {
                data.setLastKnockbackTime(now);
            }

            // 将击退信息同步到物理状态追踪器
            if (inferenceService != null) {
                dev.ztros.ansac.physics.PlayerPhysicsState pstate = inferenceService.getState(event.getPlayer().getUniqueId());
                if (pstate != null) {
                    pstate.setLastKnockbackTime(now);
                    pstate.setKnockbackMagnitude(velLen);
                    pstate.setKnockbackYaw((float) Math.toDegrees(Math.atan2(velocity.getZ(), velocity.getX())));
                }
            }
        }

        // Update location data
        if (data != null) {
            data.updateLocation(to);
        }

        // 服务端地面验证：检查玩家脚下是否有实体方块
        // 用于防止 Ground Spoofing（客户端伪造 onGround 状态）
        boolean serverVerifiedGround = verifyGround(to);
        if (inferenceService != null) {
            dev.ztros.ansac.physics.PlayerPhysicsState pstate = inferenceService.getState(event.getPlayer().getUniqueId());
            if (pstate != null) {
                pstate.setServerVerifiedGround(serverVerifiedGround);
            }
        }

        // Process movement checks
        plugin.getCheckManager().processPlayer(event.getPlayer());

        // Feed movement data to physics inference service
        if (inferenceService != null) {
            inferenceService.onPlayerMove(event.getPlayer(), data, from, to);
        }
    }

    /**
     * 服务端验证玩家是否真正在地面上。
     * 检查玩家脚下 0.6 格内是否有实体方块。
     */
    private boolean verifyGround(Location location) {
        Location loc = location.clone();
        double playerY = loc.getY();
        for (int i = 1; i <= 3; i++) {
            loc.subtract(0, 0.2, 0);
            if (loc.getBlock().getType().isSolid()) {
                double blockTopY = loc.getBlockY() + 1.0;
                double dist = playerY - blockTopY;
                if (dist >= -0.1 && dist <= 0.6) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null) return;

        // 记录伤害时间（Velocity 检测需要）
        data.setLastDamageTime(System.currentTimeMillis());

        // 判断是否为攻击伤害（玩家被另一个实体攻击）
        if (event instanceof EntityDamageByEntityEvent) {
            data.setLastDamageAttack(true);

            // 分发到 Criticals 检测（被攻击者视角）
            EntityDamageByEntityEvent damageByEntity = (EntityDamageByEntityEvent) event;
            if (damageByEntity.getDamager() instanceof org.bukkit.entity.Player attacker) {
                PlayerData attackerData = plugin.getPlayerDataManager().getPlayerData(attacker);
                if (attackerData != null) {
                    CriticalsCheck critCheck = (CriticalsCheck) plugin.getCheckManager().getCheck("Criticals");
                    if (critCheck != null) {
                        critCheck.processAttack(attacker, attackerData, damageByEntity);
                    }

                    HitboxExpandCheck hitboxCheck = (HitboxExpandCheck) plugin.getCheckManager().getCheck("HitboxExpand");
                    if (hitboxCheck != null) {
                        hitboxCheck.processAttack(attacker, attackerData, player);
                    }

                    CrystalAuraCheck crystalCheck = (CrystalAuraCheck) plugin.getCheckManager().getCheck("CrystalAura");
                    if (crystalCheck != null) {
                        crystalCheck.processAttack(attacker, attackerData, player);
                    }
                }
            }
        } else {
            data.setLastDamageAttack(false);
        }

        // Wind charge damage or explosion = knockback
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL) {
            data.setLastKnockbackTime(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) return;

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        AutoArmorCheck autoArmor = (AutoArmorCheck) plugin.getCheckManager().getCheck("AutoArmor");
        if (autoArmor != null) {
            autoArmor.processInventoryAction(player, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        BowSpamCheck bowSpam = (BowSpamCheck) plugin.getCheckManager().getCheck("BowSpam");
        if (bowSpam != null) {
            bowSpam.processBowShoot(player, data);
        }

        QuiverCheck quiver = (QuiverCheck) plugin.getCheckManager().getCheck("Quiver");
        if (quiver != null) {
            float pitch = player.getLocation().getPitch();
            quiver.processBowShoot(player, data, pitch);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        AutoTotemCheck autoTotem = (AutoTotemCheck) plugin.getCheckManager().getCheck("AutoTotem");
        if (autoTotem != null) {
            autoTotem.processOffhandSwap(player, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) return;

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        AutoEatCheck autoEat = (AutoEatCheck) plugin.getCheckManager().getCheck("AutoEat");
        if (autoEat != null) {
            autoEat.processItemConsume(player, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuitForAutoLog(PlayerQuitEvent event) {
        // Check for AutoLog before PlayerData is removed (MONITOR runs before LOWEST)
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(event.getPlayer());
        if (data != null) {
            AutoLogCheck autoLog = (AutoLogCheck) plugin.getCheckManager().getCheck("AutoLog");
            if (autoLog != null) {
                autoLog.checkDisconnect(event.getPlayer());
            }
        }
    }
}
