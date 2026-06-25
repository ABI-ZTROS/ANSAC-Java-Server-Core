package dev.ztros.ansac.punishment;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Represents a single punishment record (ban or kick).
 * Thread-safe immutable fields for core data, mutable only for active state.
 *
 * Reference: AdvancedBan Punishment.java design pattern.
 */
public class PunishmentEntry {

    @Getter
    private final UUID uuid;

    @Getter
    private final String playerName;

    @Getter
    private final String reason;

    @Getter
    private final String checkName;

    @Getter
    private final int vl;

    @Getter
    private final String operator;

    @Getter
    private final long banTime;

    @Getter
    private final long durationSeconds;

    @Getter
    @Setter
    private boolean active;

    @Getter
    private final String ip;

    public PunishmentEntry(UUID uuid, String playerName, String reason,
                           String checkName, int vl, String operator,
                           long banTime, long durationSeconds, String ip) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.reason = reason;
        this.checkName = checkName;
        this.vl = vl;
        this.operator = operator;
        this.banTime = banTime;
        this.durationSeconds = durationSeconds;
        this.active = true;
        this.ip = ip;
    }

    /**
     * Check if this ban is permanent.
     */
    public boolean isPermanent() {
        return durationSeconds < 0;
    }

    /**
     * Get the expiry timestamp in milliseconds.
     * Returns -1 for permanent bans.
     */
    public long getExpiryTime() {
        if (isPermanent()) return -1;
        return banTime + (durationSeconds * 1000L);
    }

    /**
     * Check if this ban has expired.
     */
    public boolean hasExpired() {
        if (isPermanent()) return false;
        return System.currentTimeMillis() > getExpiryTime();
    }

    /**
     * Serialize to a string array for YAML storage.
     */
    public String[] toStringArray() {
        return new String[]{
            uuid.toString(),
            playerName,
            reason,
            checkName != null ? checkName : "",
            String.valueOf(vl),
            operator,
            String.valueOf(banTime),
            String.valueOf(durationSeconds),
            String.valueOf(active),
            ip != null ? ip : ""
        };
    }

    /**
     * Deserialize from a string array (YAML storage).
     */
    public static PunishmentEntry fromStringArray(String[] parts) {
        if (parts.length < 9) return null;
        UUID uuid = UUID.fromString(parts[0]);
        String playerName = parts[1];
        String reason = parts[2];
        String checkName = parts[3].isEmpty() ? null : parts[3];
        int vl = Integer.parseInt(parts[4]);
        String operator = parts[5];
        long banTime = Long.parseLong(parts[6]);
        long durationSeconds = Long.parseLong(parts[7]);
        boolean active = Boolean.parseBoolean(parts[8]);
        String ip = parts.length > 9 ? parts[9] : null;

        PunishmentEntry entry = new PunishmentEntry(
            uuid, playerName, reason, checkName, vl, operator, banTime, durationSeconds, ip
        );
        entry.setActive(active);
        return entry;
    }
}
