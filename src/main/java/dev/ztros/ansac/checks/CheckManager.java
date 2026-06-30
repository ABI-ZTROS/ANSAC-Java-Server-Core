package dev.ztros.ansac.checks;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.building.AirPlaceCheck;
import dev.ztros.ansac.checks.building.FastBreakCheck;
import dev.ztros.ansac.checks.building.FastPlaceCheck;
import dev.ztros.ansac.checks.building.NukerCheck;
import dev.ztros.ansac.checks.building.PacketMineCheck;
import dev.ztros.ansac.checks.building.ScaffoldCheck;
import dev.ztros.ansac.checks.combat.AutoArmorCheck;
import dev.ztros.ansac.checks.combat.AutoClickerCheck;
import dev.ztros.ansac.checks.combat.AutoLogCheck;
import dev.ztros.ansac.checks.combat.AutoTotemCheck;
import dev.ztros.ansac.checks.combat.AutoTrapCheck;
import dev.ztros.ansac.checks.combat.BowAimbotCheck;
import dev.ztros.ansac.checks.combat.BowSpamCheck;
import dev.ztros.ansac.checks.combat.BurrowCheck;
import dev.ztros.ansac.checks.combat.CrystalAuraCheck;
import dev.ztros.ansac.checks.combat.CriticalsCheck;
import dev.ztros.ansac.checks.combat.HitboxExpandCheck;
import dev.ztros.ansac.checks.combat.KillAuraCheck;
import dev.ztros.ansac.checks.combat.MultiAuraCheck;
import dev.ztros.ansac.checks.combat.QuiverCheck;
import dev.ztros.ansac.checks.combat.ReachCheck;
import dev.ztros.ansac.checks.combat.SurroundCheck;
import dev.ztros.ansac.checks.combat.VelocityCheck;
import dev.ztros.ansac.checks.movement.AirJumpCheck;
import dev.ztros.ansac.checks.movement.AntiWaterPushCheck;
import dev.ztros.ansac.checks.movement.BoatFlyCheck;
import dev.ztros.ansac.checks.movement.BlinkCheck;
import dev.ztros.ansac.checks.movement.ElytraFlightCheck;
import dev.ztros.ansac.checks.movement.FastClimbCheck;
import dev.ztros.ansac.checks.movement.FlyCheck;
import dev.ztros.ansac.checks.movement.GlideCheck;
import dev.ztros.ansac.checks.movement.HighJumpCheck;
import dev.ztros.ansac.checks.movement.JesusCheck;
import dev.ztros.ansac.checks.movement.LongJumpCheck;
import dev.ztros.ansac.checks.movement.NoClipCheck;
import dev.ztros.ansac.checks.movement.NoFallCheck;
import dev.ztros.ansac.checks.movement.NoSlowCheck;
import dev.ztros.ansac.checks.movement.NoWebCheck;
import dev.ztros.ansac.checks.movement.SpeedCheck;
import dev.ztros.ansac.checks.movement.SpiderCheck;
import dev.ztros.ansac.checks.movement.StepCheck;
import dev.ztros.ansac.checks.packet.BadPacketsCheck;
import dev.ztros.ansac.checks.packet.TimerCheck;
import dev.ztros.ansac.checks.player.AntiHungerCheck;
import dev.ztros.ansac.checks.player.AutoEatCheck;
import dev.ztros.ansac.checks.player.FastUseCheck;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.physics.IPhysicsCheck;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.InferenceResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages all anti-cheat checks.
 * Handles registration, scheduling, and execution of checks.
 * On Folia, uses runAtEntity for each player to ensure thread safety.
 */
public class CheckManager {

    private final ANSACPlugin plugin;
    private final List<Check> checks = new ArrayList<>();

    public CheckManager(ANSACPlugin plugin) {
        this.plugin = plugin;
        registerChecks();
        startCheckTask();
        startMaintenanceTask();
    }

    /**
     * Register all checks
     */
    private void registerChecks() {
        // Movement checks
        checks.add(new SpeedCheck(plugin));
        checks.add(new FlyCheck(plugin));
        checks.add(new ElytraFlightCheck(plugin));
        checks.add(new NoSlowCheck(plugin));
        checks.add(new NoFallCheck(plugin));
        checks.add(new BlinkCheck(plugin));
        checks.add(new JesusCheck(plugin));
        checks.add(new SpiderCheck(plugin));
        checks.add(new HighJumpCheck(plugin));
        checks.add(new StepCheck(plugin));
        checks.add(new NoClipCheck(plugin));
        checks.add(new AirJumpCheck(plugin));
        checks.add(new GlideCheck(plugin));
        checks.add(new BoatFlyCheck(plugin));
        checks.add(new LongJumpCheck(plugin));
        checks.add(new FastClimbCheck(plugin));
        checks.add(new NoWebCheck(plugin));
        checks.add(new AntiWaterPushCheck(plugin));

        // Combat checks
        checks.add(new ReachCheck(plugin));
        checks.add(new KillAuraCheck(plugin));
        checks.add(new VelocityCheck(plugin));
        checks.add(new CriticalsCheck(plugin));
        checks.add(new HitboxExpandCheck(plugin));
        checks.add(new BowAimbotCheck(plugin));
        checks.add(new BowSpamCheck(plugin));
        checks.add(new CrystalAuraCheck(plugin));
        checks.add(new MultiAuraCheck(plugin));
        checks.add(new AutoClickerCheck(plugin));
        checks.add(new AutoArmorCheck(plugin));
        checks.add(new AutoTrapCheck(plugin));
        checks.add(new BurrowCheck(plugin));
        checks.add(new SurroundCheck(plugin));
        checks.add(new AutoTotemCheck(plugin));
        checks.add(new QuiverCheck(plugin));
        checks.add(new AutoLogCheck(plugin));

        // Building checks
        checks.add(new ScaffoldCheck(plugin));
        checks.add(new FastBreakCheck(plugin));
        checks.add(new AirPlaceCheck(plugin));
        checks.add(new FastPlaceCheck(plugin));
        checks.add(new NukerCheck(plugin));
        checks.add(new PacketMineCheck(plugin));

        // Packet checks
        checks.add(new TimerCheck(plugin));
        checks.add(new BadPacketsCheck(plugin));

        // Player checks
        checks.add(new FastUseCheck(plugin));
        checks.add(new AntiHungerCheck(plugin));
        checks.add(new AutoEatCheck(plugin));

        plugin.getLogger().info("已注册 " + checks.size() + " 项检测。");
    }

    /**
     * Start the periodic check task.
     * On Folia, schedules each player's check execution on their entity region thread.
     */
    private void startCheckTask() {
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                if (data == null || data.hasBypass()) continue;

                // Skip anti-cheat checks for unauthenticated players
                if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
                    continue;
                }

                // Use runAtEntity to ensure thread safety on Folia
                plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                    // 跨世界安全检查：如果 PlayerData 中的位置不在玩家当前世界，
                    // 更新位置以避免跨世界方块读取和距离计算。
                    Location playerLoc = player.getLocation();
                    Location dataLoc = data.getCurrentLocation();
                    if (dataLoc != null && dataLoc.getWorld() != null
                            && playerLoc.getWorld() != null
                            && !dataLoc.getWorld().equals(playerLoc.getWorld())) {
                        data.updateLocation(playerLoc);
                    }

                    for (Check check : checks) {
                        if (check.isEnabled()) {
                            try {
                                PhysicsInferenceService inferenceService = plugin.getPhysicsInferenceService();
                                if (check instanceof IPhysicsCheck ipc && inferenceService != null && inferenceService.isEnabled() && inferenceService.isPreferInference()) {
                                    InferenceResult result = inferenceService.getInferenceResult(player.getUniqueId());
                                    ipc.processWithInference(player, data, result);
                                } else {
                                    check.process(player, data);
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("检测 " + check.getName() + " 出错：" + e.getMessage());
                            }
                        }
                    }
                });
            }
        }, 1L, 1L); // Run every tick
    }

    /**
     * Start maintenance tasks: violation decay and ping updates.
     */
    private void startMaintenanceTask() {
        final int decayInterval = plugin.getAnsacConfig().getViolationDecayInterval();
        final double decayFactor = plugin.getAnsacConfig().getViolationDecayFactor();
        final int pingInterval = plugin.getAnsacConfig().getPingCheckInterval();

        // Violation decay task (runs every N seconds)
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            long decayMillis = decayInterval * 1000L;
            for (PlayerData data : plugin.getPlayerDataManager().playerDataMapValues()) {
                data.getViolationsView().values().forEach(v -> {
                    if (v.shouldDecay(decayMillis)) {
                        v.decay(decayFactor);
                    }
                });
            }
        }, decayInterval, decayInterval, TimeUnit.SECONDS);

        // Ping update task (runs every N seconds)
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                if (data != null) {
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        data.updatePing();
                    });
                }
            }
        }, pingInterval, pingInterval, TimeUnit.SECONDS);
    }

    /**
     * Process a specific player through all checks (for event-driven checks).
     * Assumes this is called from the correct region thread (e.g., PlayerMoveEvent).
     */
    public void processPlayer(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        // Skip anti-cheat checks for unauthenticated players
        if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
            return;
        }

        for (Check check : checks) {
            if (check.isEnabled()) {
                try {
                    PhysicsInferenceService inferenceService = plugin.getPhysicsInferenceService();
                    if (check instanceof IPhysicsCheck ipc && inferenceService != null && inferenceService.isEnabled() && inferenceService.isPreferInference()) {
                        InferenceResult result = inferenceService.getInferenceResult(player.getUniqueId());
                        ipc.processWithInference(player, data, result);
                    } else {
                        check.process(player, data);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("检测 " + check.getName() + " 出错：" + e.getMessage());
                }
            }
        }
    }

    /**
     * Get a check by name
     */
    public Check getCheck(String name) {
        return checks.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get number of enabled checks
     */
    public int getEnabledChecksCount() {
        return (int) checks.stream().filter(Check::isEnabled).count();
    }

    /**
     * Get total number of checks
     */
    public int getTotalChecksCount() {
        return checks.size();
    }

    /**
     * Reload all checks
     */
    public void reload() {
        for (Check check : checks) {
            check.loadConfig();
        }
        plugin.getLogger().info("已重载 " + checks.size() + " 项检测。");
    }

    /**
     * Shutdown check manager
     */
    public void shutdown() {
        checks.clear();
    }

    /**
     * Called when a player quits. Notifies all checks to clean up per-player state.
     */
    public void onPlayerQuit(UUID uuid) {
        for (Check check : checks) {
            try {
                check.onPlayerQuit(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("检测 " + check.getName() + " 清理玩家状态时出错：" + e.getMessage());
            }
        }
    }
}
