package dev.ztros.ansac.auth.session;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

public class LoginSession {

    @Getter
    private final UUID uuid;

    @Getter
    @Setter
    private String playerName;

    @Getter
    @Setter
    private String ip;

    @Getter
    @Setter
    private boolean authenticated;

    @Getter
    @Setter
    private boolean registered;

    @Getter
    @Setter
    private long joinTime;

    @Getter
    @Setter
    private long loginTime;

    @Getter
    @Setter
    private int failedAttempts;

    public LoginSession(UUID uuid, String playerName, String ip) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.ip = ip;
        this.joinTime = System.currentTimeMillis();
        this.loginTime = 0;
        this.authenticated = false;
        this.registered = false;
        this.failedAttempts = 0;
    }

    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - joinTime) / 1000;
    }

    public void markAuthenticated() {
        this.authenticated = true;
        this.loginTime = System.currentTimeMillis();
    }

    public void incrementFailedAttempts() {
        this.failedAttempts++;
    }
}
