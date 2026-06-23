package dev.ztros.ansac.auth.session;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoginSession {

    @Getter
    private final UUID uuid;

    @Getter
    @Setter
    private volatile String playerName;

    @Getter
    @Setter
    private volatile String ip;

    @Getter
    private volatile boolean authenticated;

    @Getter
    @Setter
    private volatile boolean registered;

    private final long joinTime;
    private final AtomicLong loginTime = new AtomicLong(0);
    private final AtomicInteger failedAttempts = new AtomicInteger(0);

    public LoginSession(UUID uuid, String playerName, String ip) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.ip = ip;
        this.joinTime = System.currentTimeMillis();
        this.authenticated = false;
        this.registered = false;
    }

    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - joinTime) / 1000;
    }

    public void markAuthenticated() {
        this.authenticated = true;
        this.loginTime.set(System.currentTimeMillis());
    }

    public long getLoginTime() {
        return loginTime.get();
    }

    public void setLoginTime(long loginTime) {
        this.loginTime.set(loginTime);
    }

    public int getFailedAttempts() {
        return failedAttempts.get();
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts.set(failedAttempts);
    }

    public void incrementFailedAttempts() {
        this.failedAttempts.incrementAndGet();
    }
}
