package dev.kizuna.meridian.protocol;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BodyProtobufTest {
    @Test
    public void roundTripPreservesSixRealmsMixedQiAndAllIndependentModules() {
        // 比较整个值对象，保护每个模块字段、跨语言整数精度及三类余额。
        for (Realm realm : Realm.values()) {
            BodySnapshot source = ProtocolFixtures.snapshot(realm);
            BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(source));
            assertEquals(source, restored);
            assertEquals(9, restored.totalQi(), 0);
            assertEquals(985, restored.vitality().remainingSeconds(), 0);
            assertEquals(Long.MAX_VALUE, restored.revision());
        }
    }

    @Test
    public void templateRoundTripRetainsStructureWithoutIndividualState() {
        // 模板只有来源和构型，解码不会生成角色或附带角色余额。
        BodySnapshot body = ProtocolFixtures.snapshot(Realm.AWAKENING);
        BodyTemplate template = new BodyTemplate(body.template(), body.structure());
        assertEquals(template, BodyProtobuf.decodeTemplate(BodyProtobuf.encodeTemplate(template)));
    }

    @Test
    public void explicitZeroAndFalseSurviveForABodyWithoutMeridians() {
        // 无经脉生物、零时间和零寿元支出都合法；false 不能被当成字段缺失。
        var part = new Anatomy.PartId("shell");
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "躯体", 10)),
                List.of(), List.of(), List.of(), List.of());
        var body = new BodySnapshot(1, new BodyId("body-1"),
                new BodyTemplate.Reference(new DefinitionId("sample:shell"), 1), structure, Realm.AWAKENING,
                List.of(new AnatomyState.Part(part, 0)), List.of(), List.of(), List.of(), List.of(),
                new VitalityState(0, 100, 0, false), new TimeCursor(new DefinitionId("sample:world"), "first", 0), 0);
        assertEquals(body, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body)));
        assertTrue(BodyProtobuf.toMessage(body).getVitality().hasDead());
        assertTrue(BodyProtobuf.toMessage(body).hasRevision());
    }

    @Test
    public void missingZeroCapableFieldsAreRejectedInsteadOfDefaulted() {
        // 逐层移除合法为零的字段，验证数据缺失不会被默认零或存活状态掩盖。
        var body = message();
        rejects(body.toBuilder().clearRevision().build());
        rejects(body.toBuilder().setVitality(body.getVitality().toBuilder().clearDead()).build());
        rejects(body.toBuilder().setSettledAt(body.getSettledAt().toBuilder().clearElapsedMillis()).build());
        rejects(body.toBuilder().setPartStates(0, body.getPartStates(0).toBuilder().clearHp()).build());
        rejects(body.toBuilder().setMeridianStates(0, body.getMeridianStates(0).toBuilder().clearWallQi()).build());
        rejects(body.toBuilder().clearStructure().build());
    }

    @Test
    public void unknownVersionsEnumsAndFieldsCannotBecomeDefaultState() {
        // 当前精确版本遇到未知数据必须拒绝，不能在映射时静默丢弃。
        var body = message();
        rejects(body.toBuilder().setSchemaVersion(2).build());
        rejects(body.toBuilder().setRealmValue(999).build());
        rejects(body.toBuilder().clearRealm().build());
        var qi = body.getMeridianStates(0).getLumenQi().getEntries(0).toBuilder().setPhaseValue(999);
        var inventory = body.getMeridianStates(0).getLumenQi().toBuilder().setEntries(0, qi);
        rejects(body.toBuilder().setMeridianStates(0, body.getMeridianStates(0).toBuilder().setLumenQi(inventory)).build());
        var unknown = UnknownFieldSet.newBuilder().addField(100,
                UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        rejects(body.toBuilder().setVitality(body.getVitality().toBuilder().setUnknownFields(unknown)).build());
    }

    @Test
    public void malformedBodiesStillRunCoreChecksAfterProtobufParsing() {
        // 这些载荷在 Protobuf 语法上有效，但违反身体引用、余额、上限或时序规则。
        var body = message();
        rejects(body.toBuilder().clearOrganStates().build());
        rejects(body.toBuilder().setPartStates(0, body.getPartStates(0).toBuilder().setId("missing")).build());
        rejects(body.toBuilder().setPartStates(0, body.getPartStates(0).toBuilder().setHp(101)).build());
        var state = body.getMeridianStates(0);
        var duplicate = state.getLumenQi().toBuilder().addEntries(state.getLumenQi().getEntries(0));
        rejects(body.toBuilder().setMeridianStates(0, state.toBuilder().setLumenQi(duplicate)).build());
        var excess = state.getLumenQi().toBuilder().setEntries(0, state.getLumenQi().getEntries(0).toBuilder().setAmount(1000));
        rejects(body.toBuilder().setMeridianStates(0, state.toBuilder().setLumenQi(excess)).build());
        rejects(body.toBuilder().setTransits(0, body.getTransits(0).toBuilder()
                .setArrivalMillis(body.getSettledAt().getElapsedMillis())).build());
        var graph = body.getStructure().toBuilder().setConnections(0,
                body.getStructure().getConnections(0).toBuilder().setTo("missing.port"));
        rejects(body.toBuilder().setStructure(graph).build());
    }

    @Test
    public void nonFiniteNegativeAndOverflowingValuesAreRejected() {
        // 解析器接受 NaN/Infinity，但身体账本不接受；长整数也不得溢出为负数。
        var body = message();
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            rejects(body.toBuilder().setPartStates(0, body.getPartStates(0).toBuilder().setHp(invalid)).build());
        }
        rejects(body.toBuilder().setRevision(-1).build());
        var huge = body.getStructure().toBuilder().clearParts()
                .addParts(body.getStructure().getParts(0).toBuilder().setMaxHp(Double.MAX_VALUE))
                .addParts(body.getStructure().getParts(0).toBuilder().setId("second").setMaxHp(Double.MAX_VALUE));
        rejects(body.toBuilder().setStructure(huge).build());
    }

    @Test
    public void malformedTruncatedAndOversizedInputIsRejected() {
        // 限制字节大小并拒绝截断消息，不能恢复出部分有效对象。
        byte[] valid = BodyProtobuf.encodeSnapshot(ProtocolFixtures.snapshot(Realm.AWAKENING));
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(new byte[] {(byte) 0x80}));
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(new byte[MeridianProtocol.MAX_PAYLOAD_BYTES + 1]));
    }

    @Test
    public void structureLimitsApplyToObjectsAndWireInput() {
        // 直接构造的生成对象也不能绕过列表和文本限制。
        var body = message();
        var oversized = body.toBuilder().clearPartStates().addAllPartStates(
                Collections.nCopies(MeridianProtocol.MAX_LIST_ENTRIES + 1, body.getPartStates(0))).build();
        rejects(oversized);
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.fromMessage(oversized));
        rejects(body.toBuilder().setId("x".repeat(MeridianProtocol.MAX_TEXT_BYTES + 1)).build());
    }

    private static BodyMessages.BodySnapshot message() {
        // 使用完整合法基线，后续测试每次只破坏一类约束。
        return BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.VOID_TRANSFORMATION));
    }

    private static void rejects(BodyMessages.BodySnapshot body) {
        // 故意绕过发送端包装器，模拟外部语言或不可信调用方提交的原始字节。
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(body.toByteArray()));
    }
}
