package dev.ztros.ansac.physics.mlp.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家行为完整画像。
 * 聚合移动、战斗、建造、交互、网络五个维度的行为统计。
 * <p>线程安全：使用 CopyOnWriteArrayList，支持 packet 线程与 region 线程并发访问。</p>
 */
public final class PlayerBehaviorProfile {

    // ==================== 战斗采样窗口 ====================
    private final List<CombatSample> combatSamples = new CopyOnWriteArrayList<>();
    private static final int COMBAT_MAX_SAMPLES = 50;

    // ==================== 建造采样窗口 ====================
    private final List<BuildingSample> buildingSamples = new CopyOnWriteArrayList<>();
    private static final int BUILDING_MAX_SAMPLES = 50;

    // ==================== 交互采样窗口 ====================
    private final List<InteractionSample> interactionSamples = new CopyOnWriteArrayList<>();
    private static final int INTERACTION_MAX_SAMPLES = 50;

    // ==================== 网络采样窗口 ====================
    private final List<NetworkSample> networkSamples = new CopyOnWriteArrayList<>();
    private static final int NETWORK_MAX_SAMPLES = 50;

    // ==================== 长期统计 ====================
    private long totalAttacks = 0;
    private long totalCrits = 0;
    private long totalBlocksPlaced = 0;
    private long totalBlocksBroken = 0;
    private long totalEats = 0;
    private long totalBlocks = 0;
    private long sessionStartTime;

    public PlayerBehaviorProfile() {
        this.sessionStartTime = System.currentTimeMillis();
    }

    public void addCombatSample(CombatSample sample) {
        combatSamples.add(sample);
        if (combatSamples.size() > COMBAT_MAX_SAMPLES) {
            combatSamples.remove(0);
        }
        totalAttacks++;
        if (sample.isCritical()) totalCrits++;
    }

    public void addBuildingSample(BuildingSample sample) {
        buildingSamples.add(sample);
        if (buildingSamples.size() > BUILDING_MAX_SAMPLES) {
            buildingSamples.remove(0);
        }
        totalBlocksPlaced += sample.blocksPlaced();
        if (sample.breakIntervalMs() > 0) totalBlocksBroken++;
    }

    public void addInteractionSample(InteractionSample sample) {
        interactionSamples.add(sample);
        if (interactionSamples.size() > INTERACTION_MAX_SAMPLES) {
            interactionSamples.remove(0);
        }
        if (sample.eatDurationTicks() > 0) totalEats++;
    }

    public void addNetworkSample(NetworkSample sample) {
        networkSamples.add(sample);
        if (networkSamples.size() > NETWORK_MAX_SAMPLES) {
            networkSamples.remove(0);
        }
    }

    public void incrementBlockCount() {
        totalBlocks++;
    }

    // ==================== 战斗统计 ====================

    public double getCombatCpsMean() {
        return mean(combatSamples.stream().mapToDouble(CombatSample::cps).toArray());
    }

    public double getCombatCpsStd() {
        return std(combatSamples.stream().mapToDouble(CombatSample::cps).toArray());
    }

    public double getCombatIntervalMean() {
        return mean(combatSamples.stream().mapToDouble(s -> (double) s.attackIntervalMs()).toArray());
    }

    public double getCombatIntervalStd() {
        return std(combatSamples.stream().mapToDouble(s -> (double) s.attackIntervalMs()).toArray());
    }

    public double getCritRate() {
        return totalAttacks > 0 ? (double) totalCrits / totalAttacks : 0.0;
    }

    public double getReachMean() {
        return mean(combatSamples.stream().mapToDouble(CombatSample::reachDistance).toArray());
    }

    public double getAimSmoothness() {
        // 瞄准平滑度：偏航角和俯仰角变化的标准差越小越平滑
        double yawStd = std(combatSamples.stream().mapToDouble(s -> (double) s.yawDelta()).toArray());
        double pitchStd = std(combatSamples.stream().mapToDouble(s -> (double) s.pitchDelta()).toArray());
        return 1.0 - Math.min((yawStd + pitchStd) / 360.0, 1.0);
    }

    public long getComboCount() {
        // 连击数：连续攻击间隔 < 250ms 的最大连续次数
        long maxCombo = 0, currentCombo = 0;
        for (CombatSample s : combatSamples) {
            if (s.attackIntervalMs() < 250) {
                currentCombo++;
                maxCombo = Math.max(maxCombo, currentCombo);
            } else {
                currentCombo = 0;
            }
        }
        return maxCombo;
    }

    // ==================== 建造统计 ====================

    public double getPlaceIntervalMean() {
        return mean(buildingSamples.stream().mapToDouble(s -> (double) s.placeIntervalMs()).toArray());
    }

    public double getPlaceIntervalStd() {
        return std(buildingSamples.stream().mapToDouble(s -> (double) s.placeIntervalMs()).toArray());
    }

    public double getBreakIntervalMean() {
        return mean(buildingSamples.stream().mapToDouble(s -> (double) s.breakIntervalMs()).toArray());
    }

    public double getBreakIntervalStd() {
        return std(buildingSamples.stream().mapToDouble(s -> (double) s.breakIntervalMs()).toArray());
    }

    public double getDirectionConsistencyMean() {
        return mean(buildingSamples.stream().mapToDouble(BuildingSample::directionConsistency).toArray());
    }

    public double getAirPlaceRate() {
        if (buildingSamples.isEmpty()) return 0.0;
        long airCount = buildingSamples.stream().filter(BuildingSample::isAirPlace).count();
        return (double) airCount / buildingSamples.size();
    }

    // ==================== 交互统计 ====================

    public double getEatDurationMean() {
        return mean(interactionSamples.stream().mapToDouble(InteractionSample::eatDurationTicks).toArray());
    }

    public double getBlockDurationMean() {
        return mean(interactionSamples.stream().mapToDouble(InteractionSample::blockDurationTicks).toArray());
    }

    public double getUseItemIntervalMean() {
        return mean(interactionSamples.stream().mapToDouble(s -> (double) s.useItemIntervalMs()).toArray());
    }

    public double getFastUseRate() {
        if (interactionSamples.isEmpty()) return 0.0;
        long fastCount = interactionSamples.stream().filter(InteractionSample::isFastUse).count();
        return (double) fastCount / interactionSamples.size();
    }

    // ==================== 网络统计 ====================

    public double getFlyingIntervalMean() {
        return mean(networkSamples.stream().mapToDouble(s -> (double) s.flyingPacketIntervalMs()).toArray());
    }

    public double getFlyingIntervalStd() {
        return std(networkSamples.stream().mapToDouble(s -> (double) s.flyingPacketIntervalMs()).toArray());
    }

    public double getPacketLossMean() {
        return mean(networkSamples.stream().mapToDouble(NetworkSample::packetLossRate).toArray());
    }

    public double getTimerBalanceMean() {
        return mean(networkSamples.stream().mapToDouble(s -> (double) s.timerBalance()).toArray());
    }

    // ==================== 通用统计 ====================

    public long getTotalAttacks() { return totalAttacks; }
    public long getTotalBlocksPlaced() { return totalBlocksPlaced; }
    public long getTotalBlocksBroken() { return totalBlocksBroken; }
    public long getTotalEats() { return totalEats; }
    public long getTotalBlocks() { return totalBlocks; }
    public long getSessionDurationMinutes() {
        return (System.currentTimeMillis() - sessionStartTime) / 60000;
    }

    public List<CombatSample> getCombatSamples() { return new ArrayList<>(combatSamples); }
    public List<BuildingSample> getBuildingSamples() { return new ArrayList<>(buildingSamples); }
    public List<InteractionSample> getInteractionSamples() { return new ArrayList<>(interactionSamples); }
    public List<NetworkSample> getNetworkSamples() { return new ArrayList<>(networkSamples); }

    public void reset() {
        combatSamples.clear();
        buildingSamples.clear();
        interactionSamples.clear();
        networkSamples.clear();
        totalAttacks = 0;
        totalCrits = 0;
        totalBlocksPlaced = 0;
        totalBlocksBroken = 0;
        totalEats = 0;
        totalBlocks = 0;
        sessionStartTime = System.currentTimeMillis();
    }

    // ==================== 工具方法 ====================

    private static double mean(double[] values) {
        if (values == null || values.length == 0) return 0.0;
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double std(double[] values) {
        if (values == null || values.length < 2) return 0.0;
        double m = mean(values);
        double sumSq = 0.0;
        for (double v : values) sumSq += (v - m) * (v - m);
        return Math.sqrt(sumSq / values.length);
    }
}
