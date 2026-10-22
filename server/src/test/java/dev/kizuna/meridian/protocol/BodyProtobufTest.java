package dev.kizuna.meridian.protocol;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BodyProtobufTest {
    @Test
    public void openingRoundTripKeepsEachMeridianIndependentOfDamageAndQi() {
        // 同一身体同时保存三种开拓阶段，完整或断裂都不能覆盖开拓事实及当地余额。
        var source = message();
        var definition = source.getStructure().getMeridians(0);
        var existingState = source.getMeridianStates(0);
        var unopened = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.UNOPENED).setProgress(0).build();
        var partial = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.IN_PROGRESS).setProgress(0.375).build();

        // 固定字节保护外层字段 6、内部字段号与三种阶段值，跨语言实现据此核对协议。
        assertArrayEquals(HexFormat.of().parseHex("320b0801110000000000000000"),
                BodyMessages.MeridianState.newBuilder().setOpening(unopened).build().toByteArray());
        assertArrayEquals(HexFormat.of().parseHex("080211000000000000d83f"), partial.toByteArray());
        assertArrayEquals(HexFormat.of().parseHex("080311000000000000f03f"), existingState.getOpening().toByteArray());

        var structure = source.getStructure().toBuilder()
                .addMeridians(definition.toBuilder().setId("unopened").clearPorts())
                .addMeridians(definition.toBuilder().setId("partial").clearPorts());
        var message = source.toBuilder().setStructure(structure)
                .addMeridianStates(existingState.toBuilder().setId("unopened")
                        .setCondition(BodyMessages.MeridianCondition.INTACT).setOpening(unopened))
                .addMeridianStates(existingState.toBuilder().setId("partial")
                        .setCondition(BodyMessages.MeridianCondition.SEVERED).setOpening(partial))
                .build();

        BodySnapshot restored = BodyProtobuf.decodeSnapshot(message.toByteArray());
        assertEquals(message, BodyProtobuf.toMessage(restored));
        assertEquals(List.of(new MeridianOpening(MeridianOpening.Stage.OPENED, 1),
                        new MeridianOpening(MeridianOpening.Stage.UNOPENED, 0),
                        new MeridianOpening(MeridianOpening.Stage.IN_PROGRESS, 0.375)),
                restored.meridianStates().stream().map(MeridianState::opening).toList());
        assertEquals(21, restored.totalQi(), 0);
        assertEquals(Realm.VOID_TRANSFORMATION, restored.realm());
    }

    @Test
    public void openingRejectsMissingDataAndInconsistentStagesAtTheProtocolBoundary() {
        // 零进度也必须显式写入；缺失记录或字段应拒绝，旧快照不能被猜测为已开拓。
        var body = message();
        var state = body.getMeridianStates(0);
        var opening = state.getOpening();
        rejects(body.toBuilder().setMeridianStates(0, state.toBuilder().clearOpening()).build());
        for (var invalid : List.of(opening.toBuilder().clearProgress().build(),
                opening.toBuilder().clearStage().build(),
                opening.toBuilder().setStageValue(999).build(),
                opening.toBuilder().setStage(BodyMessages.MeridianOpeningStage.UNOPENED).setProgress(0.1).build(),
                opening.toBuilder().setStage(BodyMessages.MeridianOpeningStage.IN_PROGRESS).setProgress(1).build(),
                opening.toBuilder().setProgress(Math.nextDown(1.0)).build())) {
            rejects(body.toBuilder().setMeridianStates(0, state.toBuilder().setOpening(invalid)).build());
        }

        // 百分比使用严格边界，不使用真元账本的 ULP 容差；刚开始的零进度可以保存。
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -Double.MIN_VALUE, Math.nextUp(1.0)}) {
            rejects(body.toBuilder().setMeridianStates(0,
                    state.toBuilder().setOpening(opening.toBuilder().setProgress(invalid))).build());
        }
        var justStarted = body.toBuilder().setMeridianStates(0, state.toBuilder().setOpening(opening.toBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.IN_PROGRESS).setProgress(0))).build();
        assertEquals(justStarted, BodyProtobuf.toMessage(BodyProtobuf.decodeSnapshot(justStarted.toByteArray())));
    }

    @Test
    public void roundTripPreservesSixRealmsMixedQiAndAllIndependentModules() {
        // 比较整个值对象，保护每个模块字段、跨语言整数精度及三类余额。
        Realm[] realms = Realm.values();
        for (int index = 0; index < realms.length; index++) {
            BodySnapshot source = ProtocolFixtures.snapshot(realms[index]);
            BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(source);
            // 更名仍沿用原来的 1–6 编号，已保存的二进制身体不能因此换境。
            assertArrayEquals(new byte[] {0x28, (byte) (index + 1)},
                    BodyMessages.BodySnapshot.newBuilder().setRealm(message.getRealm()).build().toByteArray());
            BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(source));
            assertEquals(source, restored);
            assertEquals(9, restored.totalQi(), 0);
            assertEquals(985, restored.vitality().remainingSeconds(), 0);
            assertEquals(Long.MAX_VALUE, restored.revision());
        }

        // 相态不再编码；六个密度区间由同一个只读工具推导。
        BodyMessages.QiPhase[] phases = {BodyMessages.QiPhase.FREE, BodyMessages.QiPhase.GAS,
                BodyMessages.QiPhase.LIQUID, BodyMessages.QiPhase.SOLID,
                BodyMessages.QiPhase.ORDERED, BodyMessages.QiPhase.COHERENT};
        var entries = message().getMeridianStates(0).getLumenQi().getEntriesList();
        assertEquals(phases.length, entries.size());
        for (int index = 0; index < phases.length; index++) {
            assertEquals(phases[index], QiDensity.phase(entries.get(index).getDensity()));
        }
    }

    @Test
    public void templateRoundTripRetainsStructureWithoutIndividualState() {
        // 模板只有来源和构型，解码不会生成角色或附带角色余额。
        BodySnapshot body = ProtocolFixtures.snapshot(Realm.WANDERING_QI);
        BodyTemplate template = new BodyTemplate(body.template(), body.structure());
        assertEquals(template, BodyProtobuf.decodeTemplate(BodyProtobuf.encodeTemplate(template)));
    }

    @Test
    public void explicitZeroAndFalseSurviveForABodyWithoutMeridians() {
        // 无经脉生物、零时间和零寿元支出都合法；false 不能被当成字段缺失。
        var part = new Anatomy.PartId("shell");
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "躯体", 10)),
                List.of(), List.of(), List.of(), List.of());
        var body = new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("body-1"),
                new BodyTemplate.Reference(new DefinitionId("sample:shell"), 1), structure, Realm.WANDERING_QI,
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
        rejects(body.toBuilder().setSchemaVersion(1).build());
        rejects(body.toBuilder().setSchemaVersion(BodySnapshot.CURRENT_SCHEMA_VERSION + 1).build());
        rejects(body.toBuilder().setRealmValue(999).build());
        rejects(body.toBuilder().clearRealm().build());
        var qi = body.getMeridianStates(0).getLumenQi().getEntries(0).toBuilder().setDensity(0);
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
        byte[] valid = BodyProtobuf.encodeSnapshot(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
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
