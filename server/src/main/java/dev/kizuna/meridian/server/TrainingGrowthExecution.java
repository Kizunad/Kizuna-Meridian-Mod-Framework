package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RaiseTrainingGrowth;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TissueGrowth;

import java.util.HashSet;

import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.INVALID_REQUEST;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.MODULE_NOT_FOUND;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.REVISION_EXHAUSTED;

/**
 * 可信宿主把组织已巩固的训练成长抬到给定值：只升不降，不越过组织训练定义的上限。
 * 用在「这个身体此前已经练过」的事实上（例如生人任了职），不经过做功和巩固；命令执行与事务暂存复算共用同一规则。
 */
public final class TrainingGrowthExecution {
    private TrainingGrowthExecution() {
        // 只做规则，不持有身体。
    }

    public static void validate(RaiseTrainingGrowth request) {
        // 协议先限制工作量、唯一目标和非负目标；组织存在与否、上限多少在锁定身体后检查。
        if (request.getTissuesCount() == 0 || request.getTissuesCount() > MeridianProtocol.MAX_LIST_ENTRIES) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_TRAINING_GROWTH);
        }
        var targets = new HashSet<String>();
        for (TissueGrowth tissue : request.getTissuesList()) {
            String id = new Anatomy.TissueId(tissue.getTissueId()).value();
            if (!targets.add(id) || !(tissue.getGrowth() >= 0) || !Double.isFinite(tissue.getGrowth())) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_TRAINING_GROWTH);
            }
        }
    }

    public static BodySnapshot apply(BodySnapshot body, RaiseTrainingGrowth request) {
        // 保留构型和其余全部状态，只在权威候选上改指定组织的训练成长与版本。
        validate(request);
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTrainingGrowth(REVISION_EXHAUSTED);
        }
        BodyMessages.BodySnapshot.Builder updated = BodyProtobuf.toMessage(body).toBuilder();
        for (TissueGrowth tissue : request.getTissuesList()) {
            raise(updated, tissue);
        }
        return BodyProtobuf.fromMessage(updated.setRevision(body.revision() + 1).build());
    }

    private static void raise(BodyMessages.BodySnapshot.Builder body, TissueGrowth target) {
        // 按组织实际 ID 找定义和状态：没有这个组织、组织没声明训练、目标越过上限，都整笔拒绝。
        BodyMessages.TissueDefinition definition = body.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getId().equals(target.getTissueId()))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.rejectTrainingGrowth(MODULE_NOT_FOUND));
        if (!definition.hasTraining() || target.getGrowth() > definition.getTraining().getMaximumGrowth()) {
            throw MeridianErrors.rejectTrainingGrowth(INVALID_REQUEST);
        }
        for (int index = 0; index < body.getTissueStatesCount(); index++) {
            BodyMessages.TissueState state = body.getTissueStates(index);
            if (state.getId().equals(target.getTissueId())) {
                double growth = Math.max(state.getTrainingGrowth(), target.getGrowth());
                body.setTissueStates(index, state.toBuilder().setTrainingGrowth(growth));
                return;
            }
        }
        throw MeridianErrors.rejectTrainingGrowth(MODULE_NOT_FOUND);
    }
}
