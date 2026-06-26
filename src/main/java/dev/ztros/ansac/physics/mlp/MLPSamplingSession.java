package dev.ztros.ansac.physics.mlp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MLP 训练采样会话管理器。
 * 负责收集受信任玩家的特征向量，支持持续自动训练模式。
 * <p>
 * 持续模式（默认）：达到目标样本数后通过回调通知外部训练，
 * 训练完成后调用 {@link #markReady()} 自动回到采集状态，无限循环。
 * </p>
 */
public final class MLPSamplingSession {
    public enum State {
        IDLE, COLLECTING, WAITING_ADMIN, TRAINING, READY
    }

    private final int targetSamples;
    private final List<double[]> samples = new ArrayList<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** 持续自动训练模式 */
    private volatile boolean continuousMode = true;

    /** 训练轮次计数 */
    private volatile int trainRound = 0;

    private volatile State state = State.IDLE;

    /**
     * 达到采样目标时的回调（在 offerSample 的 synchronized 块外调用，避免死锁）。
     * 参数为当前轮次编号。
     */
    private volatile Consumer<Integer> onTargetReached;

    public MLPSamplingSession(int targetSamples) {
        if (targetSamples <= 0) {
            throw new IllegalArgumentException("targetSamples must be positive");
        }
        this.targetSamples = targetSamples;
    }

    public void setOnTargetReached(Consumer<Integer> callback) {
        this.onTargetReached = callback;
    }

    public boolean isContinuousMode() {
        return continuousMode;
    }

    public void setContinuousMode(boolean continuousMode) {
        this.continuousMode = continuousMode;
    }

    public int getTrainRound() {
        return trainRound;
    }

    /**
     * 添加一个样本。
     * 达到目标数后：
     * - 持续模式：标记 TRAINING，在锁外触发回调
     * - 手动模式：标记 WAITING_ADMIN，通知管理员
     *
     * @return true 表示刚好达到目标数
     */
    public synchronized boolean offerSample(double[] features) {
        if (state != State.COLLECTING) {
            return false;
        }
        samples.add(features.clone());
        if (samples.size() >= targetSamples) {
            trainRound++;
            if (continuousMode) {
                state = State.TRAINING;
            } else {
                state = State.WAITING_ADMIN;
                notifyAdmins();
            }
            return true;
        }
        return false;
    }

    /**
     * 在 offerSample 返回 true 后由外部调用。
     * 在 synchronized 块外触发回调，避免死锁。
     */
    public void fireTargetReachedCallback() {
        Consumer<Integer> cb = onTargetReached;
        if (cb != null) {
            cb.accept(trainRound);
        }
    }

    private void notifyAdminsAutoComplete() {
        Component message = miniMessage.deserialize(
            "<gray>[<dark_aqua>ANSAC-MLP</dark_aqua>]</gray> " +
            "<green>第 <white>" + trainRound + "</white> 轮自动训练完成。</green> " +
            "<gray>已自动继续采集新数据（目标 " + targetSamples + " 条/轮）。</gray>"
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("ansac.admin")) {
                p.sendMessage(message);
            }
        }
    }

    private void notifyAdmins() {
        Component message = miniMessage.deserialize(
            "<gray>[<dark_aqua>ANSAC-采样</dark_aqua>]</gray> " +
            "<green>受信任玩家行为数据采样已完成 (<white>" + samples.size() + "</white> 条)。</green> " +
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

    /**
     * 开始收集样本（清空之前的样本）。
     */
    public synchronized void startCollecting() {
        samples.clear();
        state = State.COLLECTING;
    }

    /**
     * 管理员确认继续训练（手动模式用）。
     */
    public synchronized void adminContinue() {
        if (state != State.WAITING_ADMIN) {
            return;
        }
        state = State.TRAINING;
        trainRound++;
    }

    /**
     * 管理员停止采样。
     */
    public synchronized void adminStop() {
        if (state != State.WAITING_ADMIN && state != State.COLLECTING) {
            return;
        }
        samples.clear();
        state = State.IDLE;
    }

    /**
     * 训练完成后标记为就绪。
     * 持续模式下自动回到采集状态并通知管理员。
     */
    public synchronized void markReady() {
        if (state == State.TRAINING) {
            state = State.READY;
            if (continuousMode) {
                notifyAdminsAutoComplete();
                state = State.COLLECTING;
            }
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
