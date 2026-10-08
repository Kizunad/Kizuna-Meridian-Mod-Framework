package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.QiTransit;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

import java.util.Arrays;
import java.util.List;

final class ProtocolFixtures {
    private ProtocolFixtures() {
        // 固定样例供编解码与非法输入测试共用，不成为生产模板。
    }

    static BodySnapshot snapshot(Realm realm) {
        // 非人构型覆盖各模块和合法环路，证明线协议不假定人体结构。
        var part = new Anatomy.PartId("body");
        var tissue = new Anatomy.TissueId("tendril");
        var organ = new Anatomy.OrganId("filter");
        var meridian = new Anatomy.MeridianId("channel");
        var a = new Anatomy.PortId("organ.port");
        var b = new Anatomy.PortId("meridian.port");
        var c = new Anatomy.PortId("tissue.port");
        var connection = new Anatomy.ConnectionId("organ.channel");
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "触须生物", 100)),
                List.of(new Anatomy.TissueDefinition(tissue, "屈肌", new DefinitionId("sample:muscle"), part, 50, 20,
                        List.of(new Anatomy.Port(c, Anatomy.PortDirection.INPUT)))),
                List.of(new Anatomy.OrganDefinition(organ, "滤元器", new DefinitionId("sample:filter"), part, 30,
                        List.of(new Anatomy.Port(a, Anatomy.PortDirection.BIDIRECTIONAL)))),
                List.of(new Anatomy.MeridianDefinition(meridian, "环形通路", List.of(part), 100, 100,
                        1000, 1000, 5, 10, 20, List.of(new Anatomy.Port(b, Anatomy.PortDirection.BIDIRECTIONAL),
                        new Anatomy.Port(new Anatomy.PortId("channel.out"), Anatomy.PortDirection.OUTPUT)))),
                List.of(new Anatomy.Connection(connection, a, b, 0, 1),
                        new Anatomy.Connection(new Anatomy.ConnectionId("channel.organ"), b, a, 1, 2),
                        new Anatomy.Connection(new Anatomy.ConnectionId("channel.tissue"), b, c, 1, 3)));

        // 所有相态共存，并保留内腔、外壁、在途三份独立货量。
        var qi = new QiInventory(Arrays.stream(Qi.QiPhase.values())
                .map(phase -> new Qi(phase, Qi.QiAttribute.of("addon:lava"), 0.5)).toList());
        return new BodySnapshot(1, new BodyId("creature-1"),
                new BodyTemplate.Reference(new DefinitionId("sample:tendril"), 7), structure, realm,
                List.of(new AnatomyState.Part(part, 80)), List.of(new AnatomyState.Tissue(tissue, 40, 2)),
                List.of(new AnatomyState.Organ(organ, 20)),
                List.of(new MeridianState(meridian, qi, qi, 3, MeridianState.Condition.LEAKING)),
                List.of(new QiTransit("transport-1", connection, qi, Long.MAX_VALUE)),
                new VitalityState(10, 1000, 5, false),
                new TimeCursor(new DefinitionId("sample:world"), "epoch-1", Long.MAX_VALUE - 1), Long.MAX_VALUE);
    }

    static MeridianMessages.RequestEnvelope request() {
        // 固定短文本便于与独立 protoc 固定字节样例核对。
        return MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setGetBodySnapshot(MeridianMessages.GetBodySnapshot.newBuilder().setBodyId("body-1")).build();
    }
}
