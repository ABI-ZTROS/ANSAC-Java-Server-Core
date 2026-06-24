package dev.ztros.ansac.player;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 网络延迟补偿系统 (Ping Compensation System)
 *
 * 参考 GrimAC 的延迟补偿设计理念：
 * - 使用滑动窗口平均延迟，避免单点波动
 * - 根据延迟动态调整检测阈值
 * - 高延迟玩家自动获得额外容差
 * - 延迟突变检测（防止延迟突然飙升导致的误判）
 *
 * 核心公式：
 *   补偿后阈值 = 基础阈值 * (1 + 延迟补偿因子 * 平均延迟 / 1000)
 *   延迟补偿因子：Speed=0.3, Fly=0.2, Elytra=0.25, Reach=0.15
 */
public class PingCompensator {

    // 延迟采样窗口大小
    private static final int SAMPLE_WINDOW = 10;

    // 高延迟阈值（超过此值给予额外豁免）
    private static final int HIGH_PING_THRESHOLD = 300; // ms

    // 延迟突变阈值（两次采样间变化超过此值视为突变）
    private static final int PING_SPIKE_THRESHOLD = 150; // ms

    // 延迟补偿因子（各检测类型不同）
    public static final double COMPENSATION_SPEED = 0.30;
    public static final double COMPENSATION_FLY = 0.20;
    public static final double COMPENSATION_ELYTRA = 0.25;
    public static final double COMPENSATION_REACH = 0.15;
    public static final double COMPENSATION_KILLAURA = 0.10;
    public static final double COMPENSATION_TIMER = 0.05;

    // 最大允许延迟（超过此值跳过检测）
    public static final int MAX_ACCEPTABLE_PING = 500; // ms

    private final Deque<Integer> pingSamples = new ArrayDeque<>();
    private int lastPing = 0;
    private long lastPingSpikeTime = 0;

    /**
     * 记录新的延迟样本
     */
    public void addPingSample(int ping) {
        // 检测延迟突变
        if (lastPing > 0 && Math.abs(ping - lastPing) > PING_SPIKE_THRESHOLD) {
            lastPingSpikeTime = System.currentTimeMillis();
        }

        lastPing = ping;
        pingSamples.addLast(ping);
        if (pingSamples.size() > SAMPLE_WINDOW) {
            pingSamples.pollFirst();
        }
    }

    /**
     * 获取滑动窗口平均延迟
     */
    public int getAveragePing() {
        if (pingSamples.isEmpty()) return 0;
        int sum = 0;
        for (int p : pingSamples) {
            sum += p;
        }
        return sum / pingSamples.size();
    }

    /**
     * 获取最新延迟
     */
    public int getLastPing() {
        return lastPing;
    }

    /**
     * 计算补偿后的阈值
     *
     * @param baseThreshold 基础阈值
     * @param compensationFactor 补偿因子
     * @return 补偿后的阈值
     */
    public double getCompensatedThreshold(double baseThreshold, double compensationFactor) {
        int avgPing = getAveragePing();
        if (avgPing <= 0) return baseThreshold;

        // 公式: 基础阈值 * (1 + 补偿因子 * 平均延迟 / 1000)
        double multiplier = 1.0 + compensationFactor * (avgPing / 1000.0);
        return baseThreshold * multiplier;
    }

    /**
     * 计算补偿后的最大速度
     *
     * @param baseSpeed 基础最大速度
     * @param compensationFactor 补偿因子
     * @return 补偿后的最大速度
     */
    public double getCompensatedSpeed(double baseSpeed, double compensationFactor) {
        int avgPing = getAveragePing();
        if (avgPing <= 0) return baseSpeed;

        // 高延迟玩家获得额外速度容差
        double multiplier = 1.0 + compensationFactor * (avgPing / 1000.0);
        return baseSpeed * multiplier;
    }

    /**
     * 计算补偿后的缓冲 tick 数
     * 高延迟玩家需要更多连续违规才 flag
     */
    public int getCompensatedBuffer(int baseBuffer, double compensationFactor) {
        int avgPing = getAveragePing();
        if (avgPing <= 0) return baseBuffer;

        // 每 100ms 延迟增加 1 tick 缓冲
        int extraBuffer = (int) (compensationFactor * (avgPing / 100.0));
        return baseBuffer + extraBuffer;
    }

    /**
     * 检查是否应该跳过检测（延迟过高或刚发生延迟突变）
     */
    public boolean shouldSkipCheck() {
        // 延迟超过最大值，跳过检测
        if (getAveragePing() > MAX_ACCEPTABLE_PING) {
            return true;
        }

        // 刚发生延迟突变（2 秒内），跳过检测
        long timeSinceSpike = System.currentTimeMillis() - lastPingSpikeTime;
        if (timeSinceSpike < 2000L) {
            return true;
        }

        return false;
    }

    /**
     * 检查是否是高延迟玩家
     */
    public boolean isHighPing() {
        return getAveragePing() > HIGH_PING_THRESHOLD;
    }

    /**
     * 获取延迟状态描述（用于日志）
     */
    public String getPingStatus() {
        int avg = getAveragePing();
        if (avg <= 0) return "未知";
        if (avg < 100) return "优秀 (" + avg + "ms)";
        if (avg < 200) return "良好 (" + avg + "ms)";
        if (avg < 300) return "一般 (" + avg + "ms)";
        if (avg < 500) return "较高 (" + avg + "ms)";
        return "极高 (" + avg + "ms)";
    }
}
