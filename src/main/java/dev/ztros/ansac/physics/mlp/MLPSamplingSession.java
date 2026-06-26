package dev.ztros.ansac.physics.mlp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * MLP 训练采样会话管理器。
 * 负责收集受信任玩家的特征向量，并在达到目标样本数后通知管理员决策。
 */
public final class MLPSamplingSession {
    public enum State {
        IDLE, COLLECTING, WAITING_ADMIN, TRAINING, READY
    }

    private final int targetSamples;
    private final List<double[]> samples = new ArrayList<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private State state = State.IDLE;

    public MLPSamplingSession(int targetSamples) {
        if (targetSamples <= 0) {
            throw new IllegalArgumentException("targetSamples must be positive");
        }
        this.targetSamples = targetSamples;
    }

    public synchronized boolean offerSample(double[] features) {
        if (state != State.COLLECTING) {
            return false;
        }
        samples.add(features.clone());
        if (samples.size() >= targetSamples) {
            state = State.WAITING_ADMIN;
            notifyAdmins();
            return true;
        }
        return false;
    }

    private void notifyAdmins() {
        Component message = miniMessage.deserialize(
            "<gray>[<dark_aqua>ANSAC-采样</dark_aqua>]</gray> " +
            "<green>受信任玩家移动数据采样已完成 (<white>" + samples.size() + "</white> 条)。</green> " +
            "请输入 <click:run_command:/ansac sampling continue><yellow><bold>/ansac sampling continue</bold></yellow></click> " +
            "开始训练 MLP 模型，或输入 " +
            "<click:run_command:/ansac sampling stop><red><bold>/ansac sampling stop</bold></red></click> 丢弃数据。"
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("ansac.admin")) {
                p.sendMessage(message);
            }
        }
    }

    public synchronized void startCollecting() {
        samples.clear();
        state = State.COLLECTING;
    }

    public synchronized void adminContinue() {
        if (state != State.WAITING_ADMIN) {
            return;
        }
        state = State.TRAINING;
    }

    public synchronized void adminStop() {
        if (state != State.WAITING_ADMIN && state != State.COLLECTING) {
            return;
        }
        samples.clear();
        state = State.IDLE;
    }

    public synchronized void markReady() {
        if (state == State.TRAINING) {
            state = State.READY;
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getSampleCount() {
        return samples.size();
    }

    public synchronized int getTargetSamples() {
        return targetSamples;
    }

    public synchronized List<double[]> drainSamples() {
        List<double[]> copy = new ArrayList<>(samples);
        samples.clear();
        return copy;
    }
}
