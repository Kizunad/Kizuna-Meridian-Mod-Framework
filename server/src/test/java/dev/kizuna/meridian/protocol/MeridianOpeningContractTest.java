package dev.kizuna.meridian.protocol;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.OpeningMessages;
import org.junit.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.*;

/** 长期保护开拓内容与意图的跨语言边界；实际推进由运行回归覆盖。 */
public class MeridianOpeningContractTest {
    @Test
    public void nonHumanRulesPreserveMixedQiAndExactActionReferences() {
        // 引导明细可以混相；条件是不透明的附属 Protobuf，不由框架解释。
        var first = rule();
        var later = first.toBuilder().setTargetMeridianId("outer.ring").build();
        var extended = later.toBuilder().setContinuousHold(true)
                .setLumenVolumeGain(2).setGuidanceQiPerSecond(1).setGuidanceBatchQi(.25)
                .setHoldQiPerSecond(.1)
                .addEfforts(BodyMessages.OpeningEffort.newBuilder().setOrganId("filter").setWatts(2))
                .addEfforts(BodyMessages.OpeningEffort.newBuilder().setTissueId("control").setWatts(.5)).build();
        for (var valid : List.of(first, later, extended)) {
            assertEquals(valid, OpeningProtobuf.decodeRule(OpeningProtobuf.encodeRule(valid)));
            assertEquals(valid, OpeningProtobuf.toRule(OpeningProtobuf.fromRule(valid)));
        }
        assertEquals(first.getPolicyConfig(), OpeningProtobuf.fromRule(first).policyConfig());
        assertEquals(7, OpeningProtobuf.fromRule(first).actionVersion());
    }

    @Test
    public void invalidRulesFailBothWireAndDirectRegistrationBoundaries() {
        // 只保护身份、传输和费用边界；人体门槛的合法范围归 Human 回归。
        var valid = rule();
        for (var invalid : List.of(valid.toBuilder().setVersion(0).build(),
                valid.toBuilder().setActionVersion(0).build(),
                valid.toBuilder().setTargetMeridianId("Upper Ring").build(),
                valid.toBuilder().clearPolicyId().build(),
                valid.toBuilder().clearPolicyConfig().build(),
                valid.toBuilder().addEfforts(BodyMessages.OpeningEffort.newBuilder()
                        .setTissueId("attention").setWatts(0)).build(),
                valid.toBuilder().setGuidanceQiPerSecond(1).build(),
                valid.toBuilder().setHoldQiPerSecond(-1).build(),
                valid.toBuilder().setHoldQiPerSecond(Double.NaN).build(),
                valid.toBuilder().setGuidanceQiPerSecond(1).setGuidanceBatchQi(.25)
                        .setGuidanceBatchQi(Double.POSITIVE_INFINITY).build(),
                valid.toBuilder().setUnknownFields(unknown()).build())) {
            assertThrows(IllegalArgumentException.class, () -> OpeningProtobuf.decodeRule(invalid.toByteArray()));
            assertThrows(IllegalArgumentException.class, () -> OpeningProtobuf.fromRule(invalid));
        }
    }

    @Test
    public void startAndPauseCarryOnlyIntentAndRequireExplicitBodyRevision() {
        // 固定独立字节保护字段编号，零身体版本必须编码而不能靠缺省猜测。
        var start = OpeningMessages.MeridianOpeningRequest.newBuilder().setProtocolVersion(1).setRequestId("s")
                .setBodyId("b").setExpectedRevision(0).setStart(OpeningMessages.StartMeridianOpening.newBuilder()
                        .setRuleId("a:b").setRuleVersion(7)).build();
        byte[] bytes = HexFormat.of().parseHex("08011201731a0162200052070a03613a621007");
        assertArrayEquals(bytes, OpeningProtobuf.encodeRequest(start));
        assertEquals(start, OpeningProtobuf.decodeRequest(bytes));
        var pause = start.toBuilder().setPause(OpeningMessages.PauseMeridianOpening.newBuilder().setMeridianId("channel")).build();
        assertEquals(pause, OpeningProtobuf.decodeRequest(OpeningProtobuf.encodeRequest(pause)));

        // 请求中没有可信时间、消耗或进度字段；上传额外字段仍按严格边界拒绝。
        for (var invalid : List.of(start.toBuilder().clearExpectedRevision().build(),
                start.toBuilder().setExpectedRevision(-1).build(),
                start.toBuilder().clearOperation().build(),
                start.toBuilder().setProtocolVersion(2).build(),
                start.toBuilder().setStart(start.getStart().toBuilder().setRuleVersion(0)).build(),
                pause.toBuilder().setPause(pause.getPause().toBuilder().setMeridianId("Bad ID")).build(),
                start.toBuilder().setUnknownFields(unknown()).build())) {
            assertThrows(IllegalArgumentException.class, () -> OpeningProtobuf.encodeRequest(invalid));
            assertThrows(IllegalArgumentException.class, () -> OpeningProtobuf.decodeRequest(invalid.toByteArray()));
        }
    }

    private static BodyMessages.MeridianOpeningRule rule() {
        // 使用环状非人构型和短规则，数值仅用于整体契约保护，不是 Human 默认值。
        return BodyMessages.MeridianOpeningRule.newBuilder().setId("sample:open_ring").setVersion(2)
                .setDisplayName("环脉适应").setTargetMeridianId("channel")
                .setPolicyId("sample:opening").setPolicyConfig(SampleOpeningPolicy.configuration(5000, false, false))
                .setActionId("sample:focus").setActionVersion(7).build();
    }

    private static UnknownFieldSet unknown() {
        // 模拟跨语言上传当前契约无法解释的字段，防止映射时静默丢弃。
        return UnknownFieldSet.newBuilder().addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
    }
}
