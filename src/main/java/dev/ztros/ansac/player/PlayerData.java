package dev.ztros.ansac.player;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.violation.ViolationData;
import dev.ztros.ansac.physics.mlp.profile.*;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores all anti-cheat related data for a player.
 * Thread-safe design for Folia compatibility.
 */
public class PlayerData {

    @Getter
    private final UUID uuid;

    @Getter
    private final Player player;

    @Getter
    private final ANSACPlugin plugin;

    // Movement tracking
    @Getter @Setter
    private Location lastLocation;

    @Getter @Setter
    private Location currentLocation;

    @Getter @Setter
    private long lastMoveTime;

    @Getter @Setter
    private double lastMotionX, lastMotionY, lastMotionZ;

    // Combat tracking
    @Getter @Setter
    private long lastAttackTime;

    @Getter @Setter
    private int attackCount;

    @Getter @Setter
    private long lastSwingTime;

    // Packet tracking
    @Getter @Setter
    private long lastFlyingPacket;

    @Getter @Setter
    private int flyingPacketCount;

    @Getter @Setter
    private double lastDeltaY;

    // Violation tracking - thread-safe
    private final Map<String, ViolationData> violations = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, ViolationData> violationsView = Collections.unmodifiableMap(violations);

    @Getter @Setter
    private boolean alertsEnabled = true;

    @Getter @Setter
    private boolean bypass = false;

    @Getter @Setter
    private int ping = 0;

    @Getter @Setter
    private long joinTime;

    // ==================== 行为画像 ====================
    @Getter
    private final PlayerBehaviorProfile behaviorProfile = new PlayerBehaviorProfile();

    // --- Buffer fields for consecutive violation counting ---

    @Getter @Setter
    private int hoverBuffer = 0;

    @Getter @Setter
    private int ascendBuffer = 0;

    @Getter @Setter
    private int fallBuffer = 0;

    @Getter @Setter
    private int speedBuffer = 0;

    @Getter @Setter
    private int noSwingBuffer = 0;

    @Getter @Setter
    private int fastClickBuffer = 0;

    @Getter @Setter
    private int rotationBuffer = 0;

    @Getter @Setter
    private long timerBalance = 0;

    @Getter @Setter
    private long lastJumpTime = 0;

    // Knockback tracking (wind charge, explosions, etc.)
    @Getter @Setter
    private long lastKnockbackTime = 0;

    // Ping compensation
    @Getter
    private final PingCompensator pingCompensator = new PingCompensator();

    // Reach check tracking
    @Getter @Setter
    private int reachBuffer = 0;

    @Getter @Setter
    private int reachAngleBuffer = 0;

    // NoSlow check tracking
    @Getter @Setter
    private int noSlowBuffer = 0;

    // NoFall check tracking
    @Getter @Setter
    private double lastGroundY = Double.MIN_VALUE;

    @Getter @Setter
    private int noFallBuffer = 0;

    // Elytra tracking
    @Getter @Setter
    private int elytraHoverBuffer = 0;

    @Getter @Setter
    private int elytraStopBuffer = 0;

    @Getter @Setter
    private double lastGlideSpeed = 0;

    // Block interaction tracking (for future Scaffold/FastBreak checks)
    @Getter @Setter
    private long lastDiggingTime = 0;

    @Getter @Setter
    private long lastUseItemTime = 0;

    @Getter @Setter
    private long lastBlockPlaceTime = 0;

    @Getter @Setter
    private int blockPlaceCount = 0;

    // Velocity check tracking
    @Getter @Setter
    private int velocityBuffer = 0;

    @Getter @Setter
    private long lastDamageTime = 0;

    @Getter @Setter
    private boolean lastDamageAttack = false;

    // Scaffold check tracking
    @Getter @Setter
    private int scaffoldBuffer = 0;

    // FastBreak check tracking
    @Getter @Setter
    private int fastBreakBuffer = 0;

    @Getter @Setter
    private long lastDigPacketTime = 0;

    // Criticals check tracking
    @Getter
    private final List<Long> critAttackTimestamps = new CopyOnWriteArrayList<>();

    @Getter
    private final List<Long> critSuccessTimestamps = new CopyOnWriteArrayList<>();

    @Getter @Setter
    private int critPacketBuffer = 0;

    @Getter @Setter
    private int critRateBuffer = 0;

    // HitboxExpand check tracking
    @Getter @Setter
    private int hitboxExpandBuffer = 0;

    // BowAimbot check tracking
    @Getter @Setter
    private int bowAimbotBuffer = 0;

    @Getter @Setter
    private float lastBowPitch = 0;

    @Getter @Setter
    private int bowChargeTicks = 0;

    // Time-windowed click timestamps for CPS calculation
    @Getter
    private final List<Long> clickTimestamps = new CopyOnWriteArrayList<>();

    public PlayerData(Player player, ANSACPlugin plugin) {
        this.uuid = player.getUniqueId();
        this.player = player;
        this.plugin = plugin;
        this.currentLocation = player.getLocation().clone();
        this.lastLocation = player.getLocation().clone();
        this.lastMoveTime = System.currentTimeMillis();
        this.lastAttackTime = 0;
        this.attackCount = 0;
        this.lastSwingTime = 0;
        this.lastFlyingPacket = 0;
        this.flyingPacketCount = 0;
        this.joinTime = System.currentTimeMillis();
    }

    /**
     * Add a violation for a specific check
     */
    public void addViolation(String checkName, double severity) {
        ViolationData data = violations.computeIfAbsent(checkName, k -> new ViolationData(checkName));
        data.addViolation(severity);
    }

    /**
     * Get violation data for a specific check
     */
    public ViolationData getViolation(String checkName) {
        return violations.get(checkName);
    }

    /**
     * Get total violation level across all checks
     */
    public int getTotalVL() {
        return violations.values().stream()
                .mapToInt(ViolationData::getTotalVL)
                .sum();
    }

    /**
     * Get number of checks that have failed
     */
    public int getFailedChecksCount() {
        return (int) violations.values().stream()
                .filter(v -> v.getTotalVL() > 0)
                .count();
    }

    /**
     * Reset violations for a specific check
     */
    public void resetViolations(String checkName) {
        violations.remove(checkName);
    }

    /**
     * Reset all violations
     */
    public void resetAllViolations() {
        violations.clear();
    }

    /**
     * Update player ping
     */
    public void updatePing() {
        if (player != null && player.isOnline()) {
            this.ping = player.getPing();
        }
    }

    /**
     * Check if player has bypass permission
     */
    public boolean hasBypass() {
        return bypass || (player != null && player.hasPermission("ansac.bypass"));
    }

    /**
     * Update location from movement.
     * 当玩家跨越世界（如下界传送门）时，重置 lastLocation 为当前位置，
     * 避免跨世界 distanceSquared 调用和方块读取报错。
     */
    public void updateLocation(Location newLocation) {
        if (this.currentLocation != null) {
            // 检测世界切换：如果新旧位置不在同一世界，重置 lastLocation
            if (this.currentLocation.getWorld() != null
                    && newLocation.getWorld() != null
                    && !this.currentLocation.getWorld().equals(newLocation.getWorld())) {
                this.lastLocation = newLocation.clone();
                this.currentLocation = newLocation.clone();
                this.lastMoveTime = System.currentTimeMillis();
                return;
            }
            this.lastLocation = this.currentLocation.clone();
        }
        this.currentLocation = newLocation.clone();
        this.lastMoveTime = System.currentTimeMillis();
    }

    /**
     * Calculate horizontal distance between last and current location
     */
    public double getHorizontalDistance() {
        if (lastLocation == null || currentLocation == null) return 0.0;
        return Math.sqrt(
                Math.pow(currentLocation.getX() - lastLocation.getX(), 2) +
                Math.pow(currentLocation.getZ() - lastLocation.getZ(), 2)
        );
    }

    /**
     * Calculate vertical distance (delta Y)
     */
    public double getVerticalDistance() {
        if (lastLocation == null || currentLocation == null) return 0.0;
        return currentLocation.getY() - lastLocation.getY();
    }
}
