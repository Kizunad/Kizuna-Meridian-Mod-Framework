package dev.kizuna.meridian.server;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageContext;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 通用兜底：未指定命中时随机选取存活部位，只扣该账户，全部部位归零时判死。 */
public final class DefaultBodyDamageHandler implements BodyDamageHandler {
    @Override
    public byte[] resolve(byte[] payload) {
        // 使用已校验的权威快照选取一次命中；具体目标随损失方案保存到回执。
        DamageContext context = HealthProtobuf.decodeContext(payload);
        HealthTarget hit = selectHit(context);
        double damageHp = context.getDamage().getDamageHp();

        // 兜底只在所有部位都无 HP 时判死，关键部位及特殊生命规则由附属覆盖。
        boolean lethal = true;
        for (PartState part : context.getBody().getPartStatesList()) {
            double remainingHp = part.getHp();
            if (hit.hasPartId() && part.getId().equals(hit.getPartId())) {
                remainingHp = Math.max(0, remainingHp - damageHp);
            }
            if (remainingHp > 0) {
                lethal = false;
                break;
            }
        }

        var resolution = DamageResolution.newBuilder().setLethal(lethal)
                .addLosses(HpLoss.newBuilder().setTarget(hit).setHp(damageHp)).build();
        return HealthProtobuf.encodeResolution(resolution);
    }

    public static HealthTarget selectHit(DamageContext context) {
        // 生物附属可复用相同选点规则；上下文应先由 HealthProtobuf 解码校验。
        if (context.getDamage().hasHit()) {
            return context.getDamage().getHit();
        }

        // 不推断人类部位名、体积或权重；每个尚有 HP 的实际部位被命中的概率相同。
        List<PartState> candidates = context.getBody().getPartStatesList().stream()
                .filter(part -> part.getHp() > 0)
                .toList();
        if (candidates.isEmpty()) {
            throw MeridianErrors.rejectHealth(FailureCode.MODULE_UNAVAILABLE);
        }
        int selectedIndex = ThreadLocalRandom.current().nextInt(candidates.size());
        return HealthTarget.newBuilder().setPartId(candidates.get(selectedIndex).getId()).build();
    }
}
