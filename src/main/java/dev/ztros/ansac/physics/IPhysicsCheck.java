package dev.ztros.ansac.physics;

import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * 物理检查接口。
 * <p>
 * 所有基于物理引擎的检查都应实现此接口。
 * 提供了基于推理结果（{@link InferenceResult}）的高级处理方法，
 * 同时保留了对原始 {@code process(Player, PlayerData)} 的兼容性。
 * </p>
 * <p>
 * 实现类可以通过重写 {@link #processWithInference(Player, PlayerData, InferenceResult)} 方法
 * 来使用物理推理引擎提供的丰富状态数据进行更精确的检查。
 * </p>
 *
 * @author ANSAC Physics Engine
 * @see InferenceResult
 * @see PhysicsInferenceService
 */
public interface IPhysicsCheck {

    /**
     * 处理基于物理推理结果的检查。
     * <p>
     * 当物理推理服务可用且启用推理模式时调用。
     * 提供了完整的物理状态快照，包括速度偏差、跳跃阶段、
     * Buff 状态等信息，便于进行高精度检测。
     * </p>
     * <p>
     * 默认实现回退到 {@code process(Player, PlayerData)}，
     * 子类可重写此方法实现推理驱动的检查逻辑。
     * </p>
     *
     * @param player   被检查的玩家
     * @param data     玩家数据
     * @param inference 物理推理结果快照
     */
    default void processWithInference(Player player, PlayerData data, InferenceResult inference) {
        // 默认回退到标准 process
        // 注意：实现类从 Check 基类继承 process(Player, PlayerData)
    }
}
