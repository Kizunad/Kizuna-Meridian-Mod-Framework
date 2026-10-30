package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;

import java.util.HashSet;

/** 仅校验策略回执的自洽性，不执行或重新分配伤害。 */
final class DamageSpilloverProtobuf {
    private static final int ROUNDING_ULPS = 64;

    private DamageSpilloverProtobuf() {
        // 验证器不持有身体或模板策略。
    }

    static void validate(DamageResolution resolution) {
        // 蔓延剂量必须是同一回执明确损失的子集，且不能回流到来源目标。
        var spillover = resolution.getSpillover();
        HealthProtobuf.validateTarget(spillover.getSource());
        double overflow = spillover.getOverflowHp();
        double multiplier = spillover.getMultiplier();
        double unabsorbed = spillover.getUnabsorbedHp();
        ProtoWire.require(Double.isFinite(overflow) && overflow > 0
                && Double.isFinite(multiplier) && multiplier >= 0
                && Double.isFinite(unabsorbed) && unabsorbed >= 0, MeridianErrors.Health.RESOLUTION);
        var targets = new HashSet<>();
        for (var loss : spillover.getLossesList()) {
            var target = loss.getTarget();
            ProtoWire.require(targets.add(target) && !target.equals(spillover.getSource())
                    && resolution.getLossesList().contains(loss), MeridianErrors.Health.RESOLUTION);
        }
        double expected = overflow * multiplier;
        double allocated = spillover.getLossesList().stream().mapToDouble(loss -> loss.getHp()).sum();
        double accounted = allocated + unabsorbed;
        double tolerance = Math.ulp(expected) * ROUNDING_ULPS;
        ProtoWire.require(Double.isFinite(expected) && Double.isFinite(accounted)
                && Math.abs(expected - accounted) <= tolerance, MeridianErrors.Health.RESOLUTION);
    }
}
