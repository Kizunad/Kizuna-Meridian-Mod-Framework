package dev.kizuna.meridian.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingFactors;

/** 主动吸呼时的采样和呼吸做功修正，随身体提交和回执一起复算。 */
public record BreathingModifiers(double intake, double respiratoryWork) {
    public static final String KEY = "meridian:breathing_factors";
    public static final BreathingModifiers NORMAL = new BreathingModifiers(1, 1);

    public BreathingModifiers {
        // 两项都必须是框架可表示的正倍率，异常存档不能免掉呼吸负载。
        if (!valid(intake) || !valid(respiratoryWork)) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_BREATHING);
        }
    }

    private static boolean valid(double value) {
        // NaN 和无穷都落在合法闭区间以外。
        return value >= CapabilityModifier.MIN_MULTIPLIER && value <= CapabilityModifier.MAX_PEAK;
    }

    public static BreathingModifiers read(BodySnapshot body) {
        // 未施加过效果的身体沿用出生配置。
        Any stored = body.addonProgress().get(KEY);
        if (stored == null) {
            return NORMAL;
        }
        try {
            BreathingFactors factors = stored.unpack(BreathingFactors.class);
            return new BreathingModifiers(factors.getIntakeMultiplier(), factors.getRespiratoryWorkMultiplier());
        } catch (InvalidProtocolBufferException invalid) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_BREATHING);
        }
    }

    public Any pack() {
        // 宿主将这两个系数与其生效或结束状态放进同一笔活动。
        BreathingFactors factors = BreathingFactors.newBuilder()
                .setIntakeMultiplier(intake)
                .setRespiratoryWorkMultiplier(respiratoryWork)
                .build();
        return Any.pack(factors);
    }

    double effectiveIntake(double organMultiplier) {
        // 聚气、肺虚等器官曲线与主动吐纳效果相乘，沿用身体的总倍率边界。
        return Math.clamp(organMultiplier * intake,
                CapabilityModifier.MIN_TOTAL_MULTIPLIER, CapabilityModifier.MAX_TOTAL_MULTIPLIER);
    }
}
