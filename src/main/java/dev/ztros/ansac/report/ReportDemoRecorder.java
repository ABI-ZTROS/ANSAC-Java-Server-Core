package dev.ztros.ansac.report;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.violation.ViolationData;
import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.physics.mlp.DualInferenceResult;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * 举报Demo录制器。
 * 在玩家被举报时保存被举报玩家的完整行为数据快照，
 * 供管理员后续审查。
 */
public class ReportDemoRecorder {

    private final ANSACPlugin plugin;
    private final File demoDir;

    public ReportDemoRecorder(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.demoDir = new File(plugin.getDataFolder(), "report-demos");
        if (!demoDir.exists()) {
            demoDir.mkdirs();
        }
    }

    /**
     * 保存举报Demo快照。
     *
     * @param reporter   举报人
     * @param target     被举报玩家
     * @param reason     举报原因
     * @param targetData 被举报玩家数据
     * @param state      被举报玩家物理状态
     * @param inference  双模型推理结果（可为null）
     * @return 保存的文件名
     */
    public String saveDemo(Player reporter, Player target, String reason,
                           PlayerData targetData, PlayerPhysicsState state,
                           DualInferenceResult inference) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = timestamp + "_" + target.getName() + ".yml";
        File file = new File(demoDir, fileName);

        YamlConfiguration yaml = new YamlConfiguration();

        // ========== 基本信息 ==========
        yaml.set("info.timestamp", System.currentTimeMillis());
        yaml.set("info.time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        yaml.set("info.reporter", reporter.getName());
        yaml.set("info.reporter-uuid", reporter.getUniqueId().toString());
        yaml.set("info.target", target.getName());
        yaml.set("info.target-uuid", target.getUniqueId().toString());
        yaml.set("info.reason", reason);
        yaml.set("info.server", target.getServer().getName());

        // ========== 位置数据 ==========
        Location loc = target.getLocation();
        if (loc != null && loc.getWorld() != null) {
            yaml.set("location.world", loc.getWorld().getName());
            yaml.set("location.x", String.format("%.3f", loc.getX()));
            yaml.set("location.y", String.format("%.3f", loc.getY()));
            yaml.set("location.z", String.format("%.3f", loc.getZ()));
            yaml.set("location.yaw", String.format("%.2f", loc.getYaw()));
            yaml.set("location.pitch", String.format("%.2f", loc.getPitch()));
        }

        // ========== 速度数据 ==========
        if (state != null) {
            yaml.set("velocity.x", String.format("%.4f", state.getVelocityX()));
            yaml.set("velocity.y", String.format("%.4f", state.getVelocityY()));
            yaml.set("velocity.z", String.format("%.4f", state.getVelocityZ()));
            yaml.set("velocity.predicted-y", String.format("%.4f", state.getPredictedVelocityY()));
            yaml.set("velocity.horizontal", String.format("%.4f",
                Math.sqrt(state.getVelocityX() * state.getVelocityX() + state.getVelocityZ() * state.getVelocityZ())));
        }

        // ========== 物理状态 ==========
        if (state != null) {
            yaml.set("physics.on-ground", state.isClientOnGround());
            yaml.set("physics.server-verified-ground", state.isServerVerifiedGround());
            yaml.set("physics.is-sprinting", state.isSprinting());
            yaml.set("physics.is-sneaking", state.isSneaking());
            yaml.set("physics.is-gliding", state.isGliding());
            yaml.set("physics.is-blocking", state.isBlocking());
            yaml.set("physics.in-water", state.isInWater());
            yaml.set("physics.in-lava", state.isInLava());
            yaml.set("physics.is-climbing", state.isClimbing());
            yaml.set("physics.in-cobweb", state.isInCobweb());
            yaml.set("physics.on-ice", state.isOnIce());
            yaml.set("physics.on-blue-ice", state.isOnBlueIce());
            yaml.set("physics.on-soul-sand", state.isOnSoulSand());
            yaml.set("physics.on-slime", state.isOnSlimeBlock());
            yaml.set("physics.on-honey", state.isOnHoneyBlock());
            yaml.set("physics.in-powder-snow", state.isInPowderSnow());
            yaml.set("physics.above-bubble-column", state.isAboveBubbleColumn());
            yaml.set("physics.head-block-count", state.getHeadBlockCount());
            yaml.set("physics.ground-material", state.getGroundMaterial() != null ? state.getGroundMaterial().name() : "UNKNOWN");
            yaml.set("physics.jump-phase", state.getJumpPhase() != null ? state.getJumpPhase().name() : "NONE");
            yaml.set("physics.jump-tick-count", state.getJumpTickCount());
            yaml.set("physics.fall-distance", String.format("%.3f", state.getServerFallDistance()));
            yaml.set("physics.ticks-since-left-ground", state.getTicksSinceLeftGround());
            yaml.set("physics.speed-potion-level", state.getSpeedPotionLevel());
            yaml.set("physics.jump-boost-level", state.getJumpBoostLevel());
            yaml.set("physics.has-levitation", state.hasLevitation());
            yaml.set("physics.levitation-level", state.getLevitationLevel());
            yaml.set("physics.has-slow-falling", state.hasSlowFalling());
            yaml.set("physics.has-dolphins-grace", state.hasDolphinsGrace());
            yaml.set("physics.has-soul-speed", state.hasSoulSpeed());
            yaml.set("physics.soul-speed-level", state.getSoulSpeedLevel());
            yaml.set("physics.knockback-magnitude", String.format("%.4f", state.getKnockbackMagnitude()));
            yaml.set("physics.knockback-yaw", String.format("%.2f", state.getKnockbackYaw()));
        }

        // ========== 玩家基础数据 ==========
        if (targetData != null) {
            yaml.set("player.ping", targetData.getPing());
            yaml.set("player.total-vl", targetData.getTotalVL());
            yaml.set("player.failed-checks-count", targetData.getFailedChecksCount());
            yaml.set("player.timer-balance", targetData.getTimerBalance());
            yaml.set("player.bypass", targetData.hasBypass());
        }

        // ========== 违规详情（全方位检测数据） ==========
        if (targetData != null) {
            Map<String, ViolationData> violations = targetData.getViolationsView();
            int idx = 0;
            for (Map.Entry<String, ViolationData> entry : violations.entrySet()) {
                ViolationData vd = entry.getValue();
                if (vd.getTotalVL() > 0) {
                    String path = "violations." + idx;
                    yaml.set(path + ".check", vd.getCheckName());
                    yaml.set(path + ".vl", vd.getTotalVL());
                    yaml.set(path + ".highest-severity", String.format("%.2f", vd.getHighestSeverity()));
                    yaml.set(path + ".last-time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(vd.getLastViolationTime())));
                    idx++;
                }
            }
            yaml.set("violations.count", idx);
        }

        // ========== AI 推理数据 ==========
        if (inference != null) {
            yaml.set("inference.normal-movement-score", String.format("%.4f", inference.normalMovementScore()));
            yaml.set("inference.normal-combat-score", String.format("%.4f", inference.normalCombatScore()));
            yaml.set("inference.normal-anomaly-score", String.format("%.4f", inference.normalAnomalyScore()));
            yaml.set("inference.threat-movement-score", String.format("%.4f", inference.threatMovementScore()));
            yaml.set("inference.threat-combat-score", String.format("%.4f", inference.threatCombatScore()));
            yaml.set("inference.threat-fusion-score", String.format("%.4f", inference.threatFusionScore()));
            yaml.set("inference.is-high-risk", inference.isHighRisk());
            yaml.set("inference.should-convict", inference.shouldConvict());
            yaml.set("inference.confidence", String.format("%.4f", inference.getConfidence()));
            if (inference.getVerdictSource() != null) {
                yaml.set("inference.verdict-source", inference.getVerdictSource().name());
            }
        }

        // ========== 移动采样（最近5个） ==========
        if (state != null && state.getMovementSamples() != null) {
            var samples = state.getMovementSamples();
            int sampleIdx = 0;
            for (var sample : samples) {
                if (sampleIdx >= 5) break;
                String path = "movement-samples." + sampleIdx;
                yaml.set(path + ".delta-x", String.format("%.4f", sample.deltaX()));
                yaml.set(path + ".delta-y", String.format("%.4f", sample.deltaY()));
                yaml.set(path + ".delta-z", String.format("%.4f", sample.deltaZ()));
                yaml.set(path + ".horizontal-speed", String.format("%.4f", sample.horizontalSpeed()));
                yaml.set(path + ".on-ground", sample.onGround());
                sampleIdx++;
            }
        }

        try {
            yaml.save(file);
            plugin.getLogger().info("[举报Demo] 已保存举报快照: " + fileName);
            return fileName;
        } catch (IOException e) {
            plugin.getLogger().warning("[举报Demo] 保存失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取Demo目录。
     */
    public File getDemoDir() {
        return demoDir;
    }
}
