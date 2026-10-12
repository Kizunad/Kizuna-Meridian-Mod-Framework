package dev.kizuna.meridian.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单次计算中的账户视图；只生成候选值，不逃逸为另一份权威状态。 */
final class QiAccounts {
    record Endpoint(Object id, double capacity, double throughputPerSecond, Anatomy.PartId organPart) { }

    private final BodySnapshot body;
    private final Map<Anatomy.PortId, Endpoint> ports = new HashMap<>();
    private final Map<Anatomy.MeridianId, MeridianState> meridians;
    private final Map<Anatomy.OrganId, AnatomyState.Organ> organs;
    private final Map<Anatomy.PartId, AnatomyState.Part> parts;

    QiAccounts(BodySnapshot body) {
        // 不同模块的 record ID 类型保留身份空间，器官和经脉同名也不会串账。
        this.body = body;
        meridians = DataChecks.index(body.meridianStates(), MeridianState::id, "经脉状态");
        organs = DataChecks.index(body.organStates(), AnatomyState.Organ::id, "器官状态");
        parts = DataChecks.index(body.partStates(), AnatomyState.Part::id, "部位状态");
        for (var definition : body.structure().meridians()) {
            var endpoint = new Endpoint(definition.id(), definition.lumenCapacity(), definition.throughputPerSecond(), null);
            definition.ports().forEach(port -> ports.put(port.id(), endpoint));
        }
        for (var definition : body.structure().organs()) {
            var endpoint = new Endpoint(definition.id(), definition.qiCapacity(), definition.throughputPerSecond(), definition.partId());
            definition.ports().forEach(port -> ports.put(port.id(), endpoint));
        }
    }

    Endpoint endpoint(Anatomy.PortId port) {
        // 组织尚没有真元缓存；返回 null 让执行器明确拒绝未实现的账户类型。
        return ports.get(port);
    }

    boolean available(Endpoint endpoint) {
        // 经脉必须完整；器官和其归属部位仍有 HP 才能收发，损伤不抹除余额。
        if (endpoint.id() instanceof Anatomy.MeridianId id) {
            return meridians.get(id).condition() == MeridianState.Condition.INTACT;
        }
        AnatomyState.Organ organState = organs.get(endpoint.id());
        AnatomyState.Part partState = parts.get(endpoint.organPart());
        return organState.hp() > 0 && partState.hp() > 0;
    }

    QiInventory inventory(Endpoint endpoint) {
        // 对外只暴露可搬运缓存，加工中原料和经脉外壁不能被抽走。
        if (endpoint.id() instanceof Anatomy.MeridianId id) {
            return meridians.get(id).lumenQi();
        }
        return organs.get(endpoint.id()).buffer();
    }

    double occupied(Endpoint endpoint) {
        // 加工中的输入占器官空间，但不计入可发运缓存。
        if (endpoint.id() instanceof Anatomy.MeridianId) {
            return inventory(endpoint).totalQi();
        }
        return organs.get(endpoint.id()).occupiedQi();
    }

    void replace(Endpoint endpoint, QiInventory inventory) {
        // 只改变指定可用账户，开拓、局部损伤、外壁和加工事实保持原值。
        if (endpoint.id() instanceof Anatomy.MeridianId id) {
            MeridianState meridianState = meridians.get(id);
            var updatedMeridian = new MeridianState(id, inventory, meridianState.wallQi(),
                    meridianState.fatigue(), meridianState.condition(), meridianState.opening());
            meridians.put(id, updatedMeridian);
        } else {
            Anatomy.OrganId id = (Anatomy.OrganId) endpoint.id();
            AnatomyState.Organ organState = organs.get(id);
            var updatedOrgan = new AnatomyState.Organ(id, organState.hp(), inventory, organState.process());
            organs.put(id, updatedOrgan);
        }
    }

    AnatomyState.Organ organ(Anatomy.OrganId id) {
        // 加工执行器按实际器官 ID 取当前候选状态。
        return organs.get(id);
    }

    void replaceOrgan(AnatomyState.Organ organ) {
        // 加工完成同时移除输入账户并入产物，避免先发布半份转换。
        organs.put(organ.id(), organ);
    }

    boolean organAvailable(Anatomy.OrganDefinition definition) {
        // 无端口器官仍能加工已有缓存，失能规则不能依赖是否声明端口。
        AnatomyState.Organ organState = organs.get(definition.id());
        AnatomyState.Part partState = parts.get(definition.partId());
        return organState.hp() > 0 && partState.hp() > 0;
    }

    List<MeridianState> meridians() {
        // 保持原始顺序，减少存档与差异比较中的无意义变化。
        return body.meridianStates().stream()
                .map(state -> meridians.get(state.id()))
                .toList();
    }

    List<AnatomyState.Organ> organs() {
        // 返回不可变结果，临时索引不会成为宿主可写接口。
        return body.organStates().stream()
                .map(state -> organs.get(state.id()))
                .toList();
    }
}
