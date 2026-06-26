package dev.ztros.ansac.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.combat.AutoClickerCheck;
import dev.ztros.ansac.checks.combat.KillAuraCheck;
import dev.ztros.ansac.checks.combat.MultiAuraCheck;
import dev.ztros.ansac.checks.combat.ReachCheck;
import dev.ztros.ansac.checks.packet.BadPacketsCheck;
import dev.ztros.ansac.checks.packet.TimerCheck;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * PacketEvents listener for packet-based detection.
 * Provides low-level packet analysis for combat and movement checks.
 * On Folia, defers entity data access to the entity's region thread.
 */
public class PacketListener extends PacketListenerAbstract {

    private final ANSACPlugin plugin;

    public PacketListener(ANSACPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        // Skip packet checks for unauthenticated players
        if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
            return;
        }

        // Handle flying packets (movement)
        if (isFlyingPacket(event)) {
            handleFlyingPacket(player, data, event);
        }

        // Handle entity interaction (combat)
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            handleInteractEntity(player, data, event);
        }

        // Handle arm animation (swing)
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            handleArmAnimation(player, data);
        }

        // Handle player digging (block break)
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            handlePlayerDigging(player, data, event);
        }

        // Handle use item (eating, drinking, blocking)
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleUseItem(player, data, event);
        }

        // Handle block placement
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlacement(player, data, event);
        }
    }

    /**
     * Check if packet is a flying/movement packet
     */
    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING ||
               event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION ||
               event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION ||
               event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    /**
     * Handle flying packet for movement validation.
     * Updates TimerCheck flying packet data that was previously missing.
     */
    private void handleFlyingPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        // Pass flying packet to TimerCheck for timer speed detection
        TimerCheck timerCheck = (TimerCheck) plugin.getCheckManager().getCheck("Timer");
        if (timerCheck != null) {
            timerCheck.onFlyingPacket(player, data);
        }

        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

        // Validate rotation
        if (flying.hasRotationChanged()) {
            BadPacketsCheck badPackets = (BadPacketsCheck) plugin.getCheckManager().getCheck("BadPackets");
            if (badPackets != null) {
                badPackets.validateRotation(
                    player, data,
                    flying.getLocation().getYaw(),
                    flying.getLocation().getPitch(),
                    player.getLocation().getYaw(),
                    player.getLocation().getPitch()
                );
            }
        }

        // Validate position
        if (flying.hasPositionChanged()) {
            BadPacketsCheck badPackets = (BadPacketsCheck) plugin.getCheckManager().getCheck("BadPackets");
            if (badPackets != null) {
                badPackets.validatePosition(
                    player, data,
                    flying.getLocation().getX(),
                    flying.getLocation().getY(),
                    flying.getLocation().getZ()
                );
            }
        }
    }

    /**
     * Handle entity interaction for combat checks.
     * On Folia, defers entity data access to the entity's region thread.
     */
    private void handleInteractEntity(Player player, PlayerData data, PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);

        if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            int targetEntityId = interact.getEntityId();

            // Defer entity lookup and combat checks to the player's entity thread
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                Entity target = getEntityById(player, targetEntityId);

                if (target != null) {
                    // Process reach check
                    ReachCheck reach = (ReachCheck) plugin.getCheckManager().getCheck("Reach");
                    if (reach != null) {
                        reach.processAttack(player, data, target);
                    }

                    // Process killaura check
                    KillAuraCheck killAura = (KillAuraCheck) plugin.getCheckManager().getCheck("KillAura");
                    if (killAura != null) {
                        killAura.processAttack(player, data, target);
                    }

                    // Process multi-aura check (multi-target attacks)
                    MultiAuraCheck multiAura = (MultiAuraCheck) plugin.getCheckManager().getCheck("MultiAura");
                    if (multiAura != null) {
                        multiAura.processAttack(player, data, target);
                    }
                }
            });
        }
    }

    /**
     * Handle arm animation for click detection
     */
    private void handleArmAnimation(Player player, PlayerData data) {
        KillAuraCheck killAura = (KillAuraCheck) plugin.getCheckManager().getCheck("KillAura");
        if (killAura != null) {
            killAura.processSwing(player, data);
        }

        AutoClickerCheck autoClicker = (AutoClickerCheck) plugin.getCheckManager().getCheck("AutoClicker");
        if (autoClicker != null) {
            autoClicker.processClick(player, data);
        }
    }

    /**
     * Handle player digging packet for future FastBreak/NoSlow checks.
     */
    private void handlePlayerDigging(Player player, PlayerData data, PacketReceiveEvent event) {
        data.setLastDiggingTime(System.currentTimeMillis());
    }

    /**
     * Handle use item packet for NoSlow check enhancement.
     */
    private void handleUseItem(Player player, PlayerData data, PacketReceiveEvent event) {
        data.setLastUseItemTime(System.currentTimeMillis());

        // Dispatch to FastUse check
        dev.ztros.ansac.checks.player.FastUseCheck fastUse =
            (dev.ztros.ansac.checks.player.FastUseCheck) plugin.getCheckManager().getCheck("FastUse");
        if (fastUse != null) {
            fastUse.processItemUse(player, data);
        }
    }

    /**
     * Handle block placement for building/combat checks.
     * FastPlace only records timestamps (no world access) and can run on packet thread.
     * AirPlace, AutoTrap, and Surround access world/block data and MUST run on the
     * region thread for Folia thread safety.
     */
    private void handleBlockPlacement(Player player, PlayerData data, PacketReceiveEvent event) {
        data.setLastBlockPlaceTime(System.currentTimeMillis());
        data.setBlockPlaceCount(data.getBlockPlaceCount() + 1);

        // Get block placement location from player's current position
        Location placeLocation = player.getLocation().clone();

        // FastPlace only records timestamps, no world access - safe on packet thread
        dev.ztros.ansac.checks.building.FastPlaceCheck fastPlace =
            (dev.ztros.ansac.checks.building.FastPlaceCheck) plugin.getCheckManager().getCheck("FastPlace");
        if (fastPlace != null) {
            fastPlace.processBlockPlace(player, data);
        }

        // Checks that read block/world data must run on the region thread for Folia
        plugin.getSchedulerAdapter().runAtLocation(placeLocation, () -> {
            // Dispatch to AirPlace check
            dev.ztros.ansac.checks.building.AirPlaceCheck airPlace =
                (dev.ztros.ansac.checks.building.AirPlaceCheck) plugin.getCheckManager().getCheck("AirPlace");
            if (airPlace != null) {
                airPlace.processBlockPlace(player, data, placeLocation);
            }

            // Dispatch to AutoTrap check
            dev.ztros.ansac.checks.combat.AutoTrapCheck autoTrap =
                (dev.ztros.ansac.checks.combat.AutoTrapCheck) plugin.getCheckManager().getCheck("AutoTrap");
            if (autoTrap != null) {
                autoTrap.processBlockPlace(player, data, placeLocation);
            }

            // Dispatch to Surround check
            dev.ztros.ansac.checks.combat.SurroundCheck surround =
                (dev.ztros.ansac.checks.combat.SurroundCheck) plugin.getCheckManager().getCheck("Surround");
            if (surround != null) {
                surround.processBlockPlace(player, data, placeLocation);
            }
        });
    }

    /**
     * Get entity by ID from player's world.
     * Must be called from the correct region thread on Folia.
     */
    private Entity getEntityById(Player player, int entityId) {
        for (Entity entity : player.getWorld().getEntities()) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }
}
