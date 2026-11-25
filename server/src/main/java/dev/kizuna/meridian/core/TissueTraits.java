package dev.kizuna.meridian.core;

import com.google.protobuf.Any;

/** 从附属在身体里提交的特质计算固有能力，临时曲线仍按各自的起止时间求值。 */
public interface TissueTraits {
    String progressKey();

    Factors factors(Any progress, Anatomy.TissueDefinition tissue);

    record Factors(double endurance, double burst) {
        public static final Factors NORMAL = new Factors(1, 1);

        public Factors {
            // 特质只能调整有效能力，不能产生负预算或无穷大的做功额度。
            if (!Double.isFinite(endurance) || endurance <= 0 || !Double.isFinite(burst) || burst <= 0) {
                throw dev.kizuna.meridian.error.MeridianErrors.rejectTransport(
                        dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason.NUMERIC_LIMIT);
            }
        }
    }
}
