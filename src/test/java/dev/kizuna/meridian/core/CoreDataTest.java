package dev.kizuna.meridian.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CoreDataTest {
    private static final Anatomy.PartId PART = new Anatomy.PartId("body");
    private static final Anatomy.TissueId TISSUE = new Anatomy.TissueId("tendril.flexor");
    private static final Anatomy.OrganId ORGAN = new Anatomy.OrganId("filter");
    private static final Anatomy.MeridianId MERIDIAN = new Anatomy.MeridianId("channel");
    private static final Anatomy.PortId ORGAN_OUT = new Anatomy.PortId("filter.out");
    private static final Anatomy.PortId MERIDIAN_IN = new Anatomy.PortId("channel.in");
    private static final Anatomy.PortId MERIDIAN_OUT = new Anatomy.PortId("channel.out");
    private static final Anatomy.PortId TISSUE_IN = new Anatomy.PortId("tendril.in");
    private static final Anatomy.ConnectionId CONNECTION = new Anatomy.ConnectionId("filter.channel");
    private static final BodyTemplate.Reference TEMPLATE = new BodyTemplate.Reference(new DefinitionId("example:tendril"), 1);
    private static final TimeCursor NOW = new TimeCursor(new DefinitionId("example:world"), "world-1", 1000);

    @Test
    public void nonHumanBodyAccountsForLumenWallAndTransitSeparately() {
        // 用非人构型验证三类账户各计一次，避免人体命名成为隐含依赖。
        BodySnapshot body = body(structure(), List.of(state(3, 8)), List.of(transit("move-1", CONNECTION, 2, 2000)));

        assertEquals(3, body.lumenQiTotal(), 0);
        assertEquals(8, body.wallQiTotal(), 0);
        assertEquals(2, body.transitQiTotal(), 0);
        assertEquals(13, body.totalQi(), 0);
        assertEquals(0.01, body.structure().capacityRatio(), 0.000001);
        // 局部组织与器官 HP 不叠加到总体部位 HP。
        assertEquals(0.5, body.partHpRatio(), 0);
        assertEquals(85, body.vitality().remainingSeconds(), 0);
    }

    @Test
    public void sameStateCountDoesNotHideAnUnknownMeridian() {
        // 数量相同但 ID 不同也必须拒绝，缺失和重复同样不能蒙混过关。
        MeridianState impostor = new MeridianState(new Anatomy.MeridianId("missing"),
                QiInventory.empty(), QiInventory.empty(), 0, MeridianState.Condition.INTACT);

        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(impostor), List.of()));
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(0, 0), state(0, 0)), List.of()));
    }

    @Test
    public void allModuleStatesMustMatchTheirDefinitions() {
        // 同时覆盖逐模块缺失、HP 超限与疲劳超限，不能只验证经脉。
        var base = body(structure(), List.of(state(0, 0)), List.of());
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, List.of(), base.tissueStates(), base.organStates()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, base.partStates(), List.of(), base.organStates()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, base.partStates(), base.tissueStates(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base,
                List.of(new AnatomyState.Part(PART, 101)), base.tissueStates(), base.organStates()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, base.partStates(),
                List.of(new AnatomyState.Tissue(TISSUE, 61, 0)), base.organStates()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, base.partStates(),
                List.of(new AnatomyState.Tissue(TISSUE, 50, 11)), base.organStates()));
        assertThrows(IllegalArgumentException.class, () -> copyStates(base, base.partStates(), base.tissueStates(),
                List.of(new AnatomyState.Organ(ORGAN, 41))));
    }

    @Test
    public void capacityGrowthDoesNotCreateQiOrRewriteTemplateOrOtherBody() {
        // 只替换角色实际构型，验证出生模板、旧快照和已有真元都保持原值。
        BodyTemplate template = new BodyTemplate(TEMPLATE, structure());
        BodySnapshot original = body(template.structure(), List.of(state(3, 8)), List.of());
        var s = template.structure();
        BodyStructure grown = new BodyStructure(s.parts(), s.tissues(), s.organs(), List.of(meridian(40)), s.connections());
        BodySnapshot modified = body(grown, original.meridianStates(), List.of());

        assertEquals(11, modified.totalQi(), 0);
        assertEquals(20, template.structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(20, original.structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(40, modified.structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(TEMPLATE, modified.template());
    }

    @Test
    public void disconnectedBodyWithoutMeridiansCanHaveOrdinaryLife() {
        // 基础生命不依赖真元，空经脉列表不能导致非法身体或除零。
        BodyStructure structure = new BodyStructure(List.of(new Anatomy.PartDefinition(PART, "躯体", 100)),
                List.of(), List.of(), List.of(), List.of());
        BodySnapshot body = new BodySnapshot(1, new BodyId("creature-1"), TEMPLATE, structure, Realm.AWAKENING,
                List.of(new AnatomyState.Part(PART, 100)), List.of(), List.of(), List.of(), List.of(),
                new VitalityState(10, 100, 0, false), NOW, 0);

        assertEquals(0, body.totalQi(), 0);
        assertEquals(0, body.structure().capacityRatio(), 0);
        assertEquals(1, body.partHpRatio(), 0);
    }

    @Test
    public void structureRejectsMissingPartsDuplicateModulesAndUnownedPorts() {
        // 从合法构型分别引入重复 ID、悬空部位和端口，检查明确拒绝。
        BodyStructure s = structure();
        assertThrows(IllegalArgumentException.class, () -> new BodyStructure(s.parts(), s.tissues(), s.organs(),
                List.of(meridian(20), meridian(20)), s.connections()));
        assertThrows(IllegalArgumentException.class, () -> new BodyStructure(
                List.of(new Anatomy.PartDefinition(new Anatomy.PartId("other"), "其他", 10)),
                s.tissues(), s.organs(), s.meridians(), s.connections()));
        assertThrows(IllegalArgumentException.class, () -> withConnections(s,
                List.of(new Anatomy.Connection(CONNECTION, ORGAN_OUT, new Anatomy.PortId("missing"), 1, 1))));

        var duplicatePort = new Anatomy.OrganDefinition(new Anatomy.OrganId("second"), "第二器官",
                new DefinitionId("example:filter"), PART, 10,
                List.of(new Anatomy.Port(ORGAN_OUT, Anatomy.PortDirection.OUTPUT)));
        assertThrows(IllegalArgumentException.class, () -> new BodyStructure(s.parts(), s.tissues(),
                List.of(s.organs().get(0), duplicatePort), s.meridians(), s.connections()));
    }

    @Test
    public void graphRejectsWrongDirectionAndDuplicateEdgesButAcceptsCycles() {
        // 校验连接数据的完整性，同时确保合法循环不会被当成错误。
        BodyStructure s = structure();
        assertThrows(IllegalArgumentException.class, () -> withConnections(s,
                List.of(new Anatomy.Connection(CONNECTION, MERIDIAN_IN, ORGAN_OUT, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> withConnections(s,
                List.of(s.connections().get(0), new Anatomy.Connection(new Anatomy.ConnectionId("duplicate"),
                        ORGAN_OUT, MERIDIAN_IN, 1, 1))));

        var a = new Anatomy.Port(new Anatomy.PortId("a"), Anatomy.PortDirection.BIDIRECTIONAL);
        var b = new Anatomy.Port(new Anatomy.PortId("b"), Anatomy.PortDirection.BIDIRECTIONAL);
        var organ = new Anatomy.OrganDefinition(ORGAN, "循环器官", new DefinitionId("example:circulator"),
                PART, 10, List.of(a, b));
        var cycle = new BodyStructure(s.parts(), List.of(), List.of(organ), List.of(), List.of(
                new Anatomy.Connection(new Anatomy.ConnectionId("a.b"), a.id(), b.id(), 0, 1),
                new Anatomy.Connection(new Anatomy.ConnectionId("b.a"), b.id(), a.id(), 0, 1)));
        assertEquals(2, cycle.connections().size());
    }

    @Test
    public void accountsCannotExceedEffectiveCapacity() {
        // 内腔、外壁与疲劳各用自己的上限，不能互相借用剩余额度。
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(21, 0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(0, 21)), List.of()));
        var fatigued = new MeridianState(MERIDIAN, QiInventory.empty(), QiInventory.empty(), 11, MeridianState.Condition.LEAKING);
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(fatigued), List.of()));
    }

    @Test
    public void mixedDecimalBalancesCanExactlyFillACapacityInGameUnits() {
        // 允许二进制小数的机器尾差，同时拒绝游戏数值上实际存在的超额。
        BodyStructure s = structure();
        BodyStructure small = new BodyStructure(s.parts(), s.tissues(), s.organs(), List.of(meridian(0.3)), s.connections());
        QiInventory mixed = new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, 0.1),
                new Qi(Qi.QiPhase.GAS, Qi.QiAttribute.FIRE, 0.2)));
        BodySnapshot body = body(small, List.of(new MeridianState(MERIDIAN, mixed, QiInventory.empty(),
                0, MeridianState.Condition.INTACT)), List.of());

        assertEquals(0.3, body.totalQi(), 0.000000000000001);
        assertThrows(IllegalArgumentException.class, () -> body(small, List.of(state(0.30001, 0)), List.of()));
    }

    @Test
    public void transitsRequireUniqueIdsLiveConnectionsAndFutureArrival() {
        // 重复记录、无效线路和已到达却未结算的货量都不能进入快照。
        QiTransit transit = transit("move-1", CONNECTION, 2, 2000);
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(0, 0)), List.of(transit, transit)));
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(0, 0)),
                List.of(transit("move-1", new Anatomy.ConnectionId("missing"), 2, 2000))));
        assertThrows(IllegalArgumentException.class, () -> body(structure(), List.of(state(0, 0)),
                List.of(transit("move-1", CONNECTION, 2, 1000))));
    }

    @Test
    public void immutableInputListsCannotChangeAValidatedBody() {
        // 清空调用者原列表，再尝试修改返回列表，验证两个方向都不能篡改快照。
        BodyStructure s = structure();
        var parts = new ArrayList<>(s.parts());
        var states = new ArrayList<>(List.of(state(3, 8)));
        BodyStructure copy = new BodyStructure(parts, s.tissues(), s.organs(), s.meridians(), s.connections());
        BodySnapshot body = body(copy, states, List.of());
        parts.clear();
        states.clear();

        assertEquals(1, body.structure().parts().size());
        assertEquals(11, body.totalQi(), 0);
        assertThrows(UnsupportedOperationException.class, () -> body.meridianStates().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.meridians().get(0).ports().clear());
    }

    @Test
    public void invalidNumericIdentityAndVersionDataAreRejected() {
        // 加载未知版本或非法基础值时应失败，不能悄悄生成默认数据。
        assertThrows(IllegalArgumentException.class, () -> new Anatomy.PartDefinition(PART, "坏部位", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new AnatomyState.Tissue(TISSUE, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new VitalityState(-1, 100, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionId("unscoped"));
        assertThrows(IllegalArgumentException.class, () -> new BodyTemplate.Reference(TEMPLATE.id(), 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeCursor(NOW.domain(), NOW.epoch(), -1));
        assertThrows(NullPointerException.class, () -> new Anatomy.PartDefinition(null, "坏部位", 10));
        var b = body(structure(), List.of(state(0, 0)), List.of());
        assertThrows(IllegalArgumentException.class, () -> new BodySnapshot(2, b.id(), b.template(), b.structure(), b.realm(),
                b.partStates(), b.tissueStates(), b.organStates(), b.meridianStates(), b.transits(), b.vitality(), NOW, 0));
        assertThrows(IllegalArgumentException.class, () -> new BodySnapshot(1, b.id(), b.template(), b.structure(), b.realm(),
                b.partStates(), b.tissueStates(), b.organStates(), b.meridianStates(), b.transits(), b.vitality(), NOW, -1));
    }

    @Test
    public void realmExposesTenfoldCapacityAndControlSlots() {
        // 显式列出六境防止遗漏化虚，再验证共同的十倍容量与任务位规则。
        Realm[] realms = Realm.values();
        assertArrayEquals(new Realm[] {Realm.AWAKENING, Realm.QI_GUIDANCE, Realm.MERIDIAN_CONDENSATION,
                Realm.ESSENCE_SOLIDIFICATION, Realm.SPIRITUAL_ATTUNEMENT, Realm.VOID_TRANSFORMATION}, realms);
        for (int i = 0; i < realms.length; i++) {
            assertEquals(i + 1, realms[i].controlTasks());
            assertEquals(0.01 * Math.pow(10, i), realms[i].capacityRatio(), 0.000001);
        }
    }

    private static BodyStructure structure() {
        // 测试用触须生物覆盖器官输入、经脉输送和肌肉接收的最小连接图。
        return new BodyStructure(List.of(new Anatomy.PartDefinition(PART, "触须生物躯体", 100)),
                List.of(new Anatomy.TissueDefinition(TISSUE, "触须屈肌", new DefinitionId("example:muscle"),
                        PART, 60, 10, List.of(new Anatomy.Port(TISSUE_IN, Anatomy.PortDirection.INPUT)))),
                List.of(new Anatomy.OrganDefinition(ORGAN, "滤元器", new DefinitionId("example:filter"),
                        PART, 40, List.of(new Anatomy.Port(ORGAN_OUT, Anatomy.PortDirection.OUTPUT)))),
                List.of(meridian(20)), List.of(
                        new Anatomy.Connection(CONNECTION, ORGAN_OUT, MERIDIAN_IN, 1, 0.2),
                        new Anatomy.Connection(new Anatomy.ConnectionId("channel.tendril"), MERIDIAN_OUT, TISSUE_IN, 1, 0.5)));
    }

    private static Anatomy.MeridianDefinition meridian(double capacity) {
        // 保留同一液态参照，仅调整有效容量以验证成长不会凭空产生真元。
        return new Anatomy.MeridianDefinition(MERIDIAN, "主通路", List.of(PART), capacity, 20, 2000, 2000,
                2, 10, 10, List.of(new Anatomy.Port(MERIDIAN_IN, Anatomy.PortDirection.INPUT),
                new Anatomy.Port(MERIDIAN_OUT, Anatomy.PortDirection.OUTPUT)));
    }

    private static MeridianState state(double lumen, double wall) {
        // 两类余额显式分开，方便定位具体账户的校验错误。
        return new MeridianState(MERIDIAN, inventory(lumen), inventory(wall), 0, MeridianState.Condition.INTACT);
    }

    private static QiInventory inventory(double amount) {
        // 空账户不含零数量行，与正式数据约束保持一致。
        return amount == 0 ? QiInventory.empty() : new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, amount)));
    }

    private static QiTransit transit(String id, Anatomy.ConnectionId connection, double amount, long arrival) {
        // 在途只描述已扣源货量，本测试辅助方法不模拟运输执行。
        return new QiTransit(id, connection, inventory(amount), arrival);
    }

    private static BodySnapshot body(BodyStructure structure, List<MeridianState> states, List<QiTransit> transits) {
        // 用固定局部伤势与寿命隔离测试变量，余额及构型由每个案例提供。
        return new BodySnapshot(1, new BodyId("creature-1"), TEMPLATE, structure, Realm.AWAKENING,
                List.of(new AnatomyState.Part(PART, 50)), List.of(new AnatomyState.Tissue(TISSUE, 60, 0)),
                List.of(new AnatomyState.Organ(ORGAN, 40)), states, transits,
                new VitalityState(10, 100, 5, false), NOW, 0);
    }

    private static BodySnapshot copyStates(BodySnapshot b, List<AnatomyState.Part> parts,
                                           List<AnatomyState.Tissue> tissues, List<AnatomyState.Organ> organs) {
        // 只替换受测模块状态，保留其他合法数据，避免错误原因互相掩盖。
        return new BodySnapshot(1, b.id(), b.template(), b.structure(), b.realm(), parts, tissues, organs,
                b.meridianStates(), b.transits(), b.vitality(), b.settledAt(), b.revision());
    }

    private static BodyStructure withConnections(BodyStructure s, List<Anatomy.Connection> connections) {
        // 复用已知有效模块，仅改变连接以验证拓扑约束。
        return new BodyStructure(s.parts(), s.tissues(), s.organs(), s.meridians(), connections);
    }
}
