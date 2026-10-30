package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RevivalResult;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 复活只形成候选身体；寿元、局部恢复和一次版本提交共享健康事务。 */
final class BodyRevivalExecution {
    private static final double MINIMUM_HP_RATIO = 0.1;

    private BodyRevivalExecution() {
        // 事务处理器负责授权、去重和发布，不在此处接触实体或存储。
    }

    static BodyHealthExecution.Result calculate(BodySnapshot body, BodyCommand command, TimeCursor target) {
        // 复活必须显式针对死体，死亡等待期间不补算年龄或疲劳恢复。
        if (!body.vitality().dead()) {
            throw MeridianErrors.rejectHealth(FailureCode.BODY_ALIVE);
        }
        if (!target.domain().equals(body.settledAt().domain())
                || !target.epoch().equals(body.settledAt().epoch())) {
            throw MeridianErrors.rejectHealth(FailureCode.CLOCK_MISMATCH);
        }
        if (target.elapsedMillis() < body.settledAt().elapsedMillis()) {
            throw MeridianErrors.rejectHealth(FailureCode.TIME_NOT_ADVANCED);
        }

        double spent = Math.min(command.getReviveBody().getLifespanSeconds(), body.vitality().remainingSeconds());
        double totalSpent = totalSpent(body, spent);
        var original = BodyProtobuf.toMessage(body);
        var vitality = original.getVitality().toBuilder()
                .setDead(false /* dead */)
                .setSpentLifespanSeconds(totalSpent)
                .build();
        var candidate = original.toBuilder()
                .setRevision(body.revision() + 1)
                .setSettledAt(BodyProtobuf.toTime(target))
                .setVitality(vitality);
        restoreParts(candidate);
        restoreTissues(candidate);
        restoreOrgans(candidate);
        BodySnapshot revived = BodyProtobuf.fromMessage(candidate.build());
        return new BodyHealthExecution.Result(revived, fact(spent, revived));
    }

    private static double totalSpent(BodySnapshot body, double spent) {
        // 拒绝溢出或被浮点尾差吞掉的正数扣款，保证提交后的寿元账可复核。
        double previous = body.vitality().spentLifespanSeconds();
        if (spent == body.vitality().remainingSeconds()) {
            // 耗尽时直接对齐扣款上限，避免小数余额相加后残留一个浮点尾差。
            return Math.max(previous, body.vitality().lifespanLimitSeconds() - body.vitality().ageSeconds());
        }
        double total = previous + spent;
        if (!Double.isFinite(total) || (spent > 0 && total == previous)) {
            throw MeridianErrors.rejectHealth(FailureCode.NUMERIC_LIMIT);
        }
        return total;
    }

    private static void restoreParts(BodyMessages.BodySnapshot.Builder body) {
        // 只抬高低于底线的局部 HP，原本较健康的部位不被削弱。
        var definitions = body.getStructure().getPartsList();
        for (int index = 0; index < body.getPartStatesCount(); index++) {
            var state = body.getPartStates(index);
            double maximum = definitions.stream().filter(item -> item.getId().equals(state.getId()))
                    .findFirst().orElseThrow().getMaxHp();
            var restored = state.toBuilder()
                    .setHp(Math.max(state.getHp(), maximum * MINIMUM_HP_RATIO))
                    .build();
            body.setPartStates(index, restored);
        }
    }

    private static void restoreTissues(BodyMessages.BodySnapshot.Builder body) {
        // 有做功能力的组织耗尽余力；皮肤、骨骼等被动组织只恢复 HP。
        var definitions = body.getStructure().getTissuesList();
        for (int index = 0; index < body.getTissueStatesCount(); index++) {
            var state = body.getTissueStates(index);
            var definition = definitions.stream().filter(item -> item.getId().equals(state.getId()))
                    .findFirst().orElseThrow();
            var restored = state.toBuilder()
                    .setHp(Math.max(state.getHp(), definition.getMaxHp() * MINIMUM_HP_RATIO));
            var capacity = definition.getCapacity();
            if (capacity.getStrengthNewtons() > 0 || capacity.getBurstWatts() > 0
                    || capacity.getEnduranceJoules() > 0) {
                restored.setFatigue(definition.getFatigueCapacity());
            }
            body.setTissueStates(index, restored.build());
        }
    }

    private static void restoreOrgans(BodyMessages.BodySnapshot.Builder body) {
        // 器官缓存和疲劳保持原值，经脉损伤与开合也由原快照完整保留。
        var definitions = body.getStructure().getOrgansList();
        for (int index = 0; index < body.getOrganStatesCount(); index++) {
            var state = body.getOrganStates(index);
            double maximum = definitions.stream().filter(item -> item.getId().equals(state.getId()))
                    .findFirst().orElseThrow().getMaxHp();
            var restored = state.toBuilder()
                    .setHp(Math.max(state.getHp(), maximum * MINIMUM_HP_RATIO))
                    .build();
            body.setOrganStates(index, restored);
        }
    }

    private static HealthResult fact(double spent, BodySnapshot body) {
        // 余额不足仍提交复活，用固定回执告诉宿主寿元已经耗尽。
        RevivalResult revival = RevivalResult.newBuilder()
                .setSpentLifespanSeconds(spent)
                .setLifespanExhausted(body.vitality().remainingSeconds() == 0)
                .setRevivedAt(BodyProtobuf.toTime(body.settledAt()))
                .build();
        return HealthResult.newBuilder()
                .setDied(false /* died */)
                .setReleasedQi(BodyMessages.QiInventory.getDefaultInstance())
                .setRevival(revival)
                .build();
    }
}
