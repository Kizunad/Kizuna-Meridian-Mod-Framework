package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingProfile;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringChoice;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 将实际器官产物送入经脉；在途只保存统计关联，货物仍属于身体唯一账本。 */
final class BreathingDelivery<C> {
    private static final double MINIMUM_DELIVERY_QI = 1e-9;

    private final GatheringSession<C> session;
    private final BreathingProfile profile;
    private final Map<String, Double> arrivals = new HashMap<>();
    private long observedRevision = -1;
    private long boundaryMillis;
    private FailureCode failure = FailureCode.FAILURE_CODE_UNSPECIFIED;

    BreathingDelivery(GatheringSession<C> session, BreathingProfile profile) {
        // 生物附属指定入口与配方，配送无需知道肺或人体经脉的名称。
        this.session = session;
        this.profile = profile;
    }

    boolean advance() {
        // 身体未变化且没有到达边界时只读退出，避免闲置窗口每 tick 执行身体结算。
        BodySnapshot before = session.read();
        if (before.revision() == observedRevision && (boundaryMillis == 0 || session.now() < boundaryMillis)) {
            return false;
        }
        failure = FailureCode.FAILURE_CODE_UNSPECIFIED;
        List<String> organs = profile.getOrgansList().stream().map(organ -> organ.getOrganId()).toList();
        boolean hasBuffer = before.organStates().stream()
                .anyMatch(organ -> organs.contains(organ.id().value()) && organ.buffer().totalQi() > 0);
        if (!hasBuffer && arrivals.isEmpty()) {
            // 没有待配送产物时无需为该统计器推进身体时间，失联也不会额外结算。
            observedRevision = before.revision();
            boundaryMillis = 0;
            return false;
        }
        session.settleManual();
        BodySnapshot body = session.read();
        Set<String> existing = body.transits().stream().map(transit -> transit.id()).collect(Collectors.toSet());
        boolean delivered = false;
        for (String id : List.copyOf(arrivals.keySet())) {
            if (!existing.contains(id)) {
                double amount = arrivals.remove(id);
                if (!body.vitality().dead()) {
                    session.recordBreathingDelivery(amount);
                    delivered = true;
                }
            }
        }

        // 同一出口每次发送一批当前可容纳的产物；其余留在器官，吸收下一颗不必等这批到达。
        var routes = new HashSet<String>();
        if (!body.vitality().dead()) {
            List<GatheringChoice> choices = session.view().getChoicesList();
            for (GatheringChoice choice : choices) {
                if (!organs.contains(choice.getOrganId()) || !profile.getRecipeIdsList().contains(choice.getRecipe().getId())
                        || !routes.add(choice.getConnectionId())) {
                    continue;
                }
                dispatchOutput(choice, choices);
            }
        }
        BodySnapshot after = session.read();
        observedRevision = after.revision();
        boundaryMillis = QiTransport.nextArrivalBoundary(after).map(cursor -> cursor.elapsedMillis()).orElse(0L);
        return delivered || after.revision() != before.revision();
    }

    private void dispatchOutput(GatheringChoice choice, List<GatheringChoice> choices) {
        // 每次发送读取最新候选身体；前一个出口已经占用的容量和吞吐不可再次申请。
        BodySnapshot current = session.read();
        List<Qi> output = outputFor(current, choice, choices);
        double availableQi = output.stream().mapToDouble(Qi::getAmount).sum();
        if (availableQi <= MINIMUM_DELIVERY_QI) {
            return;
        }
        double outputVolume = output.stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
        if (outputVolume == 0) {
            failure = FailureCode.CAPACITY_EXCEEDED;
            return;
        }
        double fraction = Math.min(1, choice.getTargetRemainingVolume() / outputVolume);
        QiInventory cargo = outputCargo(output, fraction);
        double deliveredQi = cargo.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
        if (deliveredQi <= MINIMUM_DELIVERY_QI) {
            failure = FailureCode.CAPACITY_EXCEEDED;
            return;
        }
        double rate = QiTransport.availableThroughputPerSecond(current,
                new Anatomy.ConnectionId(choice.getConnectionId()));
        if (rate <= 0) {
            failure = FailureCode.THROUGHPUT_EXCEEDED;
            return;
        }

        dispatchCargo(current, choice, cargo, rate);
    }

    private List<Qi> outputFor(BodySnapshot current, GatheringChoice choice, List<GatheringChoice> choices) {
        // 一个出口可承接多个配方的不同产物，不能只识别排序第一项的属性。
        var snapshot = BodyProtobuf.toMessage(current);
        OrganState organ = snapshot.getOrganStatesList().stream()
                .filter(state -> state.getId().equals(choice.getOrganId())).findFirst().orElseThrow();
        List<String> recipeIds = profile.getRecipeIdsList();
        List<Qi> outputKinds = choices.stream()
                .filter(candidate -> candidate.getConnectionId().equals(choice.getConnectionId()))
                .map(GatheringChoice::getRecipe)
                .filter(recipe -> recipeIds.contains(recipe.getId()))
                .flatMap(recipe -> recipe.getOutput().getEntriesList().stream())
                .toList();
        return organ.getBuffer().getEntriesList().stream()
                .filter(item -> outputKinds.stream().anyMatch(kind ->
                        QiDensity.phase(kind.getDensity()) == QiDensity.phase(item.getDensity())
                        && kind.getAttribute().equals(item.getAttribute())))
                .toList();
    }

    private static QiInventory outputCargo(List<Qi> output, double fraction) {
        // 逐种按比例取货，复用最小单位及实际可扣性；被跳过的尘埃继续留在器官缓存。
        QiInventory wireInventory = QiInventory.newBuilder().addAllEntries(output).build();
        dev.kizuna.meridian.core.QiInventory sources = BodyProtobuf.fromInventory(wireInventory);
        var entries = new ArrayList<dev.kizuna.meridian.core.Qi>();
        for (dev.kizuna.meridian.core.Qi source : sources.entries()) {
            var inventory = new dev.kizuna.meridian.core.QiInventory(List.of(source));
            dev.kizuna.meridian.core.QiInventory selected = inventory.take(source.amount() * fraction,
                    Double.POSITIVE_INFINITY);
            entries.addAll(selected.entries());
        }
        return BodyProtobuf.toInventory(new dev.kizuna.meridian.core.QiInventory(entries));
    }

    private void dispatchCargo(BodySnapshot current, GatheringChoice choice, QiInventory cargo, double rate) {
        // 统计只关联新批次身份，货物仍由权威在途账本保存。
        Set<String> previousTransitIds = current.transits().stream()
                .map(transit -> transit.id()).collect(Collectors.toSet());
        var transport = TransportQi.newBuilder()
                .setConnectionId(choice.getConnectionId())
                .setCargo(cargo)
                .setThroughputPerSecond(rate)
                .build();
        try {
            session.execute(BodyCommand.newBuilder().setTransportQi(transport));
            for (var transit : session.read().transits()) {
                String transitId = transit.id();
                if (!previousTransitIds.contains(transitId)) {
                    arrivals.put(transitId, transit.cargo().totalQi());
                }
            }
        } catch (MeridianErrors.GatheringRejected rejection) {
            // 拥堵或受损时保留缓存等待状态变化；权限拒绝和内部故障仍交给宿主处理。
            switch (rejection.code()) {
                case CAPACITY_EXCEEDED, ROUTE_UNAVAILABLE, MODULE_UNAVAILABLE, THROUGHPUT_EXCEEDED ->
                        failure = rejection.code();
                default -> throw rejection;
            }
        }
    }

    FailureCode failure() {
        // 可恢复的配送阻塞只作反馈；不停止呼吸，也不把未到货的缓存算作已经纳入。
        return failure;
    }

    long nextBoundaryMillis() {
        // 已保存的真实运输到达才需要唤醒，堵塞后由新的身体状态再次触发。
        return boundaryMillis;
    }
}
