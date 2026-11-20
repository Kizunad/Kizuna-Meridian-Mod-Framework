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

import java.util.List;

/** 接触摄入按实时浓度和已登记配方比例计算，环境与器官结果由同一事务发布。 */
final class QiCaptureExecution {
    private static final double CAPACITY_TOLERANCE = 1e-9;
    private static final double FREE_QI_DENSITY = 0.01;
    private QiCaptureExecution() {
        // 不保存余额或捕获次数，重试由既有命令回执去重。
    }

    record Result(BodySnapshot body, EnvironmentQiSource source, QiCaptureResult fact) { }

    private record Context(BodySnapshot body, Anatomy.OrganDefinition organ, AnatomyState.Organ state,
                           OrganRecipe recipe, EnvironmentQiSource source) { }

    private record Amounts(QiInventory absorbed, QiInventory output, QiInventory released) { }

    static Result calculate(BodySnapshot body, CaptureQi request, BodyMessages.OrganRecipe definition,
                            EnvironmentQiSource source) {
        // 候选身体、环境交换与回执一同交给事务发布，任何一步失败都不修改原账本。
        Context context = context(body, request, definition, source);
        Amounts amounts = amounts(context, request.getSampleVolumeBlocks());
        BodySnapshot updated = updateBody(context, amounts, QiCaptureRouting.of(body, definition, amounts.output()));
        EnvironmentQiExchange exchange = exchange(body, source, amounts);
        EnvironmentQiSource updatedSource = EnvironmentAbsorption.apply(source, exchange);
        EnvironmentQiReceipt receipt = EnvironmentQiReceipt.newBuilder()
                .setExchange(exchange)
                .setBodyRevision(updated.revision())
                .setSourceRevision(updatedSource.getRevision())
                .build();
        QiCaptureResult fact = QiCaptureResult.newBuilder()
                .setRecipe(definition)
                .setEnvironment(receipt)
                .build();
        return new Result(updated, updatedSource, fact);
    }

    private static Context context(BodySnapshot body, CaptureQi request, BodyMessages.OrganRecipe definition,
                                    EnvironmentQiSource source) {
        // 实际器官与可信配方必须匹配；瞬时转换仍遵守身体状态和境界。
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
        if (!organ.kind().equals(recipe.organKind())
                || body.realm().capacityRatio() < recipe.minimumRealm().capacityRatio()) {
            throw MeridianErrors.rejectIntake(FailureCode.RECIPE_INCOMPATIBLE);
        }
        return new Context(body, organ, state, recipe, source);
    }

    private static Amounts amounts(Context context, double sampleVolume) {
        // 每颗粒子代表固定体积；属性由粒子选择，总剂量随当前环境浓度变化。
        EnvironmentQiSource source = context.source();
        OrganRecipe recipe = context.recipe();
        QiInventory environment = BodyProtobuf.fromInventory(source.getInventory());
        Qi inputKind = recipe.input().entries().get(0);
        Qi sourceQi = environment.entries().stream()
                .filter(item -> item.phase() == inputKind.phase() && item.attribute().equals(inputKind.attribute()))
                .findFirst().orElseThrow(() -> MeridianErrors.rejectIntake(FailureCode.INSUFFICIENT_QI));
        double availableRaw = sourceQi.amount();
        double concentration = environment.totalQi() / source.getSpace().getVolumeBlocks();
        double organMultiplier = OrganCapabilities.intakeMultiplier(context.body(), context.organ().id());
        double sampleMultiplier = BreathingModifiers.read(context.body()).effectiveIntake(organMultiplier);
        double desiredRaw = concentration * sampleVolume * sampleMultiplier;
        if (!Double.isFinite(desiredRaw)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }

        double room = room(context);
        double outputVolumePerInputQi = recipe.output().volume() / recipe.input().totalQi();
        double perSecond = context.organ().intake().maxQiPerSecond() * sampleMultiplier;
        double budget = QiCaptureBudget.available(context.body(), context.organ().id(), perSecond);
        if (budget <= 0) {
            throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
        }
        double offered = Math.min(Math.min(desiredRaw, availableRaw), room / outputVolumePerInputQi);
        double rawAmount = Math.min(offered, budget);
        if (rawAmount <= 0) {
            throw MeridianErrors.rejectIntake(FailureCode.INSUFFICIENT_QI);
        }
        return refine(recipe, sourceQi.withAmount(rawAmount));
    }

    private static double room(Context context) {
        // 在途和加工原料预占真实容量，空间不足时只收能保存的部分。
        Anatomy.OrganDefinition organ = context.organ();
        BodySnapshot body = context.body();
        List<Anatomy.PortId> ports = organ.ports().stream().map(Anatomy.Port::id).toList();
        List<Anatomy.ConnectionId> incoming = body.structure().connections().stream()
                .filter(route -> ports.contains(route.to())).map(Anatomy.Connection::id).toList();
        double reserved = body.transits().stream().filter(transit -> incoming.contains(transit.connectionId()))
                .mapToDouble(transit -> transit.cargo().volume()).sum();
        double room = Math.max(0, organ.volumeCapacity() - context.state().occupiedVolume() - reserved);
        if (room <= CAPACITY_TOLERANCE) {
            throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
        }
        return room;
    }

    private static Amounts refine(OrganRecipe recipe, Qi incoming) {
        // 按配方比例转换，损耗返回原环境，输入、产物和释放三者守恒。
        double rawAmount = incoming.amount();
        QiInventory absorbed = new QiInventory(List.of(incoming));
        double scale = rawAmount / recipe.input().totalQi();
        QiInventory output = new QiInventory(recipe.output().entries().stream()
                .map(item -> new Qi(item.attribute(), item.amount() * scale, item.density())).toList());
        double loss = Math.max(0, rawAmount - output.totalQi());
        QiInventory released = QiInventory.empty();
        if (loss > 0) {
            released = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, loss, FREE_QI_DENSITY)));
        }
        return new Amounts(absorbed, output, released);
    }

    private static BodySnapshot updateBody(Context context, Amounts amounts, QiCaptureRouting.Routing routing) {
        // 生成身体候选与开脉的新摄入证据，已有加工和疲劳不被本次接触覆盖。
        // 配方写了去处的产物直接记进对应经脉的内腔，其余的进器官缓存，同一笔提交。
        BodySnapshot body = context.body();
        AnatomyState.Organ state = context.state();
        double rawAmount = amounts.absorbed().totalQi();
        QiInventory buffer = QiAccounting.credit(state.buffer(), routing.kept());
        var organ = BodyProtobuf.toOrganState(state).toBuilder().setBuffer(BodyProtobuf.toInventory(buffer)).build();
        var candidate = BodyProtobuf.toMessage(body).toBuilder()
                .setOrganStates(body.organStates().indexOf(state), organ)
                .putAllAddonProgress(QiCaptureBudget.consume(body, state.id(), rawAmount))
                .setRevision(body.revision() + 1);
        for (int index = 0; index < body.meridianStates().size(); index++) {
            QiInventory cargo = routing.lumen().get(body.meridianStates().get(index).id());
            if (cargo != null) {
                QiInventory lumen = QiAccounting.credit(body.meridianStates().get(index).lumenQi(), cargo);
                candidate.setMeridianStates(index, candidate.getMeridianStates(index).toBuilder()
                        .setLumenQi(BodyProtobuf.toInventory(lumen)));
            }
        }
        var message = candidate.build();
        BodySnapshot updated = BodyProtobuf.fromMessage(message);
        return OpeningRuntime.recordIntake(updated, state.id(), rawAmount);
    }

    private static EnvironmentQiExchange exchange(BodySnapshot body, EnvironmentQiSource source, Amounts amounts) {
        // 原始量先扣环境，损耗回同一来源；两个版本和回执一起提交，不重复进入默认释放账。
        BodyMessages.TimeCursor now = BodyProtobuf.toTime(body.settledAt());
        EnvironmentQiOffer offer = EnvironmentAbsorption.offer(source, body.id().value(), now, now);
        return EnvironmentQiExchange.newBuilder()
                .setExchangeId("capture-" + (body.revision() + 1))
                .setOffer(offer)
                .setExpectedBodyRevision(body.revision())
                .setStartedAt(now)
                .setEndedAt(now)
                .setContactCapture(true /* contactCapture */)
                .setAbsorbedQi(BodyProtobuf.toInventory(amounts.absorbed()))
                .setReleasedQi(BodyProtobuf.toInventory(amounts.released()))
                .build();
    }
}
