package dev.ztros.ansac.listeners;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.checks.CheckManager;
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

            // Notify auth service
            if (plugin.getAuthService().isEnabled()) {
                String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
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
        if (data == null) return;

        // Update ping sample for latency compensation
        data.getPingCompensator().addPingSample(data.getPing());

        // Detect sudden velocity changes (wind charge, explosion knockback, etc.)
        Vector velocity = event.getPlayer().getVelocity();
        double velLen = velocity.length();
        if (velLen > 1.5) {
            data.setLastKnockbackTime(System.currentTimeMillis());
        }

        // Update location data
        data.updateLocation(to);

        // Process movement checks
        plugin.getCheckManager().processPlayer(event.getPlayer());
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
