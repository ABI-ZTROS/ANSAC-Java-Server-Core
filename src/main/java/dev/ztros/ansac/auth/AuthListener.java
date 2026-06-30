package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

public class AuthListener implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private final AuthConfig authConfig;

    public AuthListener(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
        this.authConfig = authService.getAuthConfig();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!authConfig.isRestrictMovement()) return;

        Player player = event.getPlayer();
        if (authService.isAuthenticated(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        // Allow rotation-only and natural falling
        if (from.getX() == to.getX() && from.getZ() == to.getZ() && from.getY() - to.getY() >= 0.0) {
            return;
        }

        // 回退到 from 位置而不是取消事件
        // 取消事件会导致 MONITOR 优先级的监听器（包括反作弊推理）无法收到移动数据
        // 改为回退位置，让事件仍然传播但玩家不会实际移动
        event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
            to.getYaw(), to.getPitch()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!authConfig.isRestrictChat()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!authConfig.isRestrictCommands()) return;

        Player player = event.getPlayer();
        if (authService.isAuthenticated(player.getUniqueId())) return;

        String message = event.getMessage();
        boolean allowed = authConfig.isCommandAllowed(message);
        plugin.getLogger().info("[AuthListener] 命令检查：" + message + " 允许=" + allowed + " 玩家=" + player.getName());

        if (allowed) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(MINI_MESSAGE.deserialize(
                "<gray>[<aqua>ANSAC</gray>] <yellow>请先登录。用法：<white>/register <密码> <确认密码><yellow> 或 <white>/login <密码>"
        ));
        plugin.getLogger().info("[AuthListener] 已拦截未认证玩家的命令：" + player.getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!authConfig.isRestrictItems()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (!authConfig.isRestrictItems()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!authConfig.isRestrictInventory()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!authConfig.isRestrictInventory()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
