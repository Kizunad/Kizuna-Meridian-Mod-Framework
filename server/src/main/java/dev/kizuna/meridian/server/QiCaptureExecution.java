package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.CaptureProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

import java.util.ArrayList;
import java.util.List;

/** 接触摄入按实时浓度和已登记配方比例计算，环境与器官结果由同一事务发布。 */
final class QiCaptureExecution {
    private QiCaptureExecution() {
        // 不保存余额或捕获次数，重试由既有命令回执去重。
    }

    record Result(BodySnapshot body, EnvironmentQiSource source, QiCaptureResult fact) { }

    static Result calculate(BodySnapshot body, CaptureQi request, BodyMessages.OrganRecipe definition,
                            EnvironmentQiSource source) {
        // 先读取实际器官与可信配方；瞬时转换仍遵守身体状态、内容类型和境界。
        CaptureProtobuf.validateRequest(request);
        OrganRecipe recipe = CapabilityProtobuf.fromRecipe(definition);
        if (!recipe.id().value().equals(request.getRecipeId()) || recipe.version() != request.getRecipeVersion()
                || recipe.input().entries().size() != 1 || recipe.output().totalQi() <= 0) {
            throw MeridianErrors.rejectIntake(FailureCode.RECIPE_INCOMPATIBLE);
        }
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
        Anatomy.OrganDefinition organ = body.structure().organs().stream()
                .filter(item -> item.id().value().equals(request.getOrganId())).findFirst()
                .orElseThrow(() -> MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND));
        AnatomyState.Organ state = body.organStates().stream()
                .filter(item -> item.id().equals(organ.id())).findFirst().orElseThrow();
        double partHp = body.partStates().stream().filter(item -> item.id().equals(organ.partId()))
                .findFirst().orElseThrow().hp();
        if (state.hp() <= 0 || partHp <= 0) {
            throw MeridianErrors.rejectIntake(FailureCode.MODULE_UNAVAILABLE);
        }
        if (organ.intake() == null) {
            throw MeridianErrors.rejectIntake(FailureCode.INTAKE_UNSUPPORTED);
        }
        if (!organ.kind().equals(recipe.organKind()) || body.realm().capacityRatio() < recipe.minimumRealm().capacityRatio()) {
            throw MeridianErrors.rejectIntake(FailureCode.RECIPE_INCOMPATIBLE);
        }

        // 每颗粒子代表固定体积；属性由粒子选择，总剂量随当前环境浓度变化。
        BodyMessages.TimeCursor now = BodyProtobuf.toTime(body.settledAt());
        EnvironmentQiOffer offer = EnvironmentAbsorption.offer(source, body.id().value(), now, now);
        QiInventory environment = BodyProtobuf.fromInventory(source.getInventory());
        Qi inputKind = recipe.input().entries().get(0);
        double availableRaw = environment.entries().stream()
                .filter(item -> item.phase() == inputKind.phase() && item.attribute().equals(inputKind.attribute()))
                .mapToDouble(Qi::amount).sum();
        double concentration = environment.totalQi() / source.getSpace().getVolumeBlocks();
        double desiredRaw = concentration * request.getSampleVolumeBlocks();
        if (!Double.isFinite(desiredRaw)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }

        // 器官缓存仍是实际账户；预留在途和加工原料占容，空间不足时只收能保存的部分。
        List<Anatomy.PortId> ports = organ.ports().stream().map(Anatomy.Port::id).toList();
        List<Anatomy.ConnectionId> incoming = body.structure().connections().stream()
                .filter(route -> ports.contains(route.to())).map(Anatomy.Connection::id).toList();
        double reserved = body.transits().stream().filter(transit -> incoming.contains(transit.connectionId()))
                .mapToDouble(transit -> transit.cargo().totalQi()).sum();
        double room = Math.max(0, organ.qiCapacity() - state.occupiedQi() - reserved);
        double efficiency = recipe.output().totalQi() / recipe.input().totalQi();
        double rawAmount = Math.min(Math.min(desiredRaw, availableRaw), room / efficiency);
        if (rawAmount <= 0) {
            throw MeridianErrors.rejectIntake(room <= 0 ? FailureCode.CAPACITY_EXCEEDED : FailureCode.INSUFFICIENT_QI);
        }
        QiInventory absorbed = new QiInventory(List.of(new Qi(inputKind.phase(), inputKind.attribute(), rawAmount)));
        double scale = rawAmount / recipe.input().totalQi();
        QiInventory output = new QiInventory(recipe.output().entries().stream()
                .map(item -> new Qi(item.phase(), item.attribute(), item.amount() * scale)).toList());
        double loss = Math.max(0, rawAmount - output.totalQi());
        QiInventory released = QiInventory.empty();
        if (loss > 0) {
            released = new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, loss)));
        }

        // 生成身体候选与开脉的新摄入证据，已有加工和疲劳不被本次接触覆盖。
        var organs = new ArrayList<>(body.organStates());
        int index = organs.indexOf(state);
        organs.set(index, new AnatomyState.Organ(state.id(), state.hp(), QiAccounting.credit(state.buffer(), output),
                state.process(), state.fatigueJoules()));
        BodySnapshot updated = new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), organs, body.meridianStates(), body.transits(), body.vitality(),
                body.settledAt(), body.revision() + 1, body.actions(), body.openingTask());
        updated = OpeningRuntime.recordIntake(updated, organ.id(), rawAmount);

        // 原始量先扣环境，损耗回同一来源；两个版本和回执一起提交，不重复进入默认释放账。
        EnvironmentQiExchange exchange = EnvironmentQiExchange.newBuilder().setExchangeId("capture-" + updated.revision())
                .setOffer(offer).setExpectedBodyRevision(body.revision()).setStartedAt(now).setEndedAt(now)
                .setContactCapture(true).setAbsorbedQi(BodyProtobuf.toInventory(absorbed))
                .setReleasedQi(BodyProtobuf.toInventory(released)).build();
        EnvironmentQiSource updatedSource = EnvironmentAbsorption.apply(source, exchange);
        EnvironmentQiReceipt receipt = EnvironmentQiReceipt.newBuilder().setExchange(exchange)
                .setBodyRevision(updated.revision()).setSourceRevision(updatedSource.getRevision()).build();
        QiCaptureResult fact = QiCaptureResult.newBuilder().setRecipe(definition).setEnvironment(receipt).build();
        return new Result(updated, updatedSource, fact);
    }
}
