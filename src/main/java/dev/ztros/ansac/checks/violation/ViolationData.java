package dev.ztros.ansac.checks.violation;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe violation tracking for individual checks.
 */
public class ViolationData {

    @Getter
    private final String checkName;

    private final AtomicInteger violationLevel = new AtomicInteger(0);

    private final AtomicLong lastViolationTime = new AtomicLong(0);

    private final AtomicReference<Double> highestSeverity = new AtomicReference<>(0.0);

    public ViolationData(String checkName) {
        this.checkName = checkName;
    }

    /**
     * Add a violation with severity
     */
    public void addViolation(double severity) {
        violationLevel.incrementAndGet();
        lastViolationTime.set(System.currentTimeMillis());
        // Thread-safe max update
        highestSeverity.updateAndGet(current -> Math.max(current, severity));
    }

    /**
     * Get current violation level
     */
    public int getTotalVL() {
        return violationLevel.get();
    }

    /**
     * Get last violation time
     */
    public long getLastViolationTime() {
        return lastViolationTime.get();
    }

    /**
     * Get highest severity
     */
    public double getHighestSeverity() {
        return highestSeverity.get();
    }

    /**
     * Decay violations using atomic compare-and-set loop.
     */
    public void decay(double factor) {
        int current;
        int newVL;
        do {
            current = violationLevel.get();
            if (current <= 0) return;
            newVL = (int) Math.max(0, current * factor);
        } while (!violationLevel.compareAndSet(current, newVL));
    }

    /**
     * Reset violations to zero
     */
    public void reset() {
        violationLevel.set(0);
        highestSeverity.set(0.0);
    }

    /**
     * Check if violations should decay based on time
     */
    public boolean shouldDecay(long decayMillis) {
        return System.currentTimeMillis() - lastViolationTime.get() > decayMillis;
    }
}
