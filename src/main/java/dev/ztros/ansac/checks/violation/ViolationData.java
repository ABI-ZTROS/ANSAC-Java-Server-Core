package dev.ztros.ansac.checks.violation;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe violation tracking for individual checks.
 */
public class ViolationData {

    @Getter
    private final String checkName;

    private final AtomicInteger violationLevel = new AtomicInteger(0);

    @Getter
    private long lastViolationTime;

    @Getter
    private double highestSeverity;

    public ViolationData(String checkName) {
        this.checkName = checkName;
        this.lastViolationTime = 0;
        this.highestSeverity = 0.0;
    }

    /**
     * Add a violation with severity
     */
    public void addViolation(double severity) {
        violationLevel.incrementAndGet();
        lastViolationTime = System.currentTimeMillis();
        if (severity > highestSeverity) {
            highestSeverity = severity;
        }
    }

    /**
     * Get current violation level
     */
    public int getTotalVL() {
        return violationLevel.get();
    }

    /**
     * Decay violations (called periodically)
     */
    public void decay(double factor) {
        int current = violationLevel.get();
        if (current > 0) {
            int newVL = (int) Math.max(0, current * factor);
            violationLevel.set(newVL);
        }
    }

    /**
     * Reset violations to zero
     */
    public void reset() {
        violationLevel.set(0);
        highestSeverity = 0.0;
    }

    /**
     * Check if violations should decay based on time
     */
    public boolean shouldDecay(long decayMillis) {
        return System.currentTimeMillis() - lastViolationTime > decayMillis;
    }
}
