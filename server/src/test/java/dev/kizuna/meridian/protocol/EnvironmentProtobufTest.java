package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiExchange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiOffer;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiReceipt;
import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class EnvironmentProtobufTest {
    @Test
    public void offerMatchesFixedWireContractIncludingExplicitZeroValues() {
        // 固定字节保护来源、身体、版本、账户、速率和有效区间，合法零值不能被当成缺省。
        byte[] wire = HexFormat.of().parseHex(
                "080112017318002201622a00310000000000000000"
                + "3a110a0a746573743a636c6f636b1201651800"
                + "42120a0a746573743a636c6f636b12016518e807");
        EnvironmentQiOffer offer = offer().setAvailableQi(BodyMessages.QiInventory.getDefaultInstance())
                .setMaxAbsorptionPerSecond(0).build();
        assertArrayEquals(wire, EnvironmentProtobuf.encodeOffer(offer));
        assertEquals(offer, EnvironmentProtobuf.decodeOffer(wire));
    }

    @Test
    public void committedExchangePreservesBothDirectionsAndCannotBecomeClientCommand() {
        // 保存原始吸收与不同相态的释放，往返后不能丢失自定义属性或重复解释成客户端操作。
        EnvironmentQiExchange exchange = exchange().build();
        var receipt = EnvironmentQiReceipt.newBuilder().setExchange(exchange)
                .setBodyRevision(1).setSourceRevision(1).build();
        assertEquals(exchange, EnvironmentProtobuf.decodeExchange(EnvironmentProtobuf.encodeExchange(exchange)));
        byte[] receiptBytes = EnvironmentProtobuf.encodeReceipt(receipt);
        assertEquals(receipt, EnvironmentProtobuf.decodeReceipt(receiptBytes));
        for (byte[] internalMessage : new byte[][] {
                EnvironmentProtobuf.encodeOffer(exchange.getOffer()),
                EnvironmentProtobuf.encodeExchange(exchange), receiptBytes
        }) {
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(internalMessage));
        }

        // 瞬时纯释放不占吸收带宽，无环境库存时也能记录死亡或加工释放。
        EnvironmentQiExchange release = exchange().setOffer(offer().clearAvailableQi()
                        .setAvailableQi(BodyMessages.QiInventory.getDefaultInstance()).setMaxAbsorptionPerSecond(0))
                .setEndedAt(time(0)).setAbsorbedQi(BodyMessages.QiInventory.getDefaultInstance()).build();
        assertEquals(release, EnvironmentProtobuf.decodeExchange(EnvironmentProtobuf.encodeExchange(release)));
    }

    @Test
    public void malformedSupplyCannotEnterEitherCodecBoundary() {
        // 跨语言输入同样要求身份、显式字段、有限速率和统一时钟，账户不得重复同类明细。
        EnvironmentQiOffer offer = offer().build();
        for (EnvironmentQiOffer invalid : new EnvironmentQiOffer[] {
                offer.toBuilder().setProtocolVersion(2).build(),
                offer.toBuilder().clearSourceId().build(),
                offer.toBuilder().clearBodyId().build(),
                offer.toBuilder().clearSourceRevision().build(),
                offer.toBuilder().setSourceRevision(-1).build(),
                offer.toBuilder().clearAvailableQi().build(),
                offer.toBuilder().setAvailableQi(offer.getAvailableQi().toBuilder()
                        .addAllEntries(offer.getAvailableQi().getEntriesList())).build(),
                offer.toBuilder().clearMaxAbsorptionPerSecond().build(),
                offer.toBuilder().setMaxAbsorptionPerSecond(Double.NaN).build(),
                offer.toBuilder().setMaxAbsorptionPerSecond(Double.POSITIVE_INFINITY).build(),
                offer.toBuilder().setMaxAbsorptionPerSecond(-1).build(),
                offer.toBuilder().clearValidFrom().build(),
                offer.toBuilder().setValidFrom(time(0).toBuilder().clearElapsedMillis()).build(),
                offer.toBuilder().setValidUntil(time(0)).build(),
                offer.toBuilder().setValidUntil(time(1000).toBuilder().setEpoch("other")).build(),
                offer.toBuilder().setValidUntil(time(1000).toBuilder().setDomain("other:clock")).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.encodeOffer(invalid));
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.decodeOffer(invalid.toByteArray()));
        }
    }

    @Test
    public void absorptionChecksGrossAmountsKindsAndTimeBudget() {
        // 基准交换在一秒内吸收 2 元、释放 1 元；净额不能掩盖超速、错种类或超出库存。
        for (EnvironmentQiExchange invalid : new EnvironmentQiExchange[] {
                exchange().setOffer(offer().setAvailableQi(inventory(BodyMessages.QiPhase.FREE, "meridian:none", 1))).build(),
                exchange().setAbsorbedQi(inventory(BodyMessages.QiPhase.GAS, "meridian:none", 2)).build(),
                exchange().setAbsorbedQi(inventory(BodyMessages.QiPhase.FREE, "addon:other", 2)).build(),
                exchange().setOffer(offer().setMaxAbsorptionPerSecond(1)).build(),
                exchange().setOffer(offer().setMaxAbsorptionPerSecond(0)).build(),
                exchange().setEndedAt(time(999)).build(),
                exchange().setEndedAt(time(0)).build(),
                exchange().setEndedAt(time(1001)).build(),
                exchange().setOffer(offer().setValidFrom(time(1))).build(),
                exchange().setStartedAt(time(1000)).setEndedAt(time(0)).build(),
                exchange().setStartedAt(time(0).toBuilder().setEpoch("other")).build(),
                exchange().setEndedAt(time(1000).toBuilder().setDomain("other:clock")).build(),
                exchange().clearExpectedBodyRevision().build(),
                exchange().clearReleasedQi().build(),
                exchange().clearExchangeId().build(),
                exchange().setAbsorbedQi(BodyMessages.QiInventory.getDefaultInstance())
                        .setReleasedQi(BodyMessages.QiInventory.getDefaultInstance()).build(),
                exchange().setAbsorbedQi(BodyMessages.QiInventory.getDefaultInstance())
                        .setStartedAt(time(1000)).setEndedAt(time(1000)).build(),
                exchange().setExpectedBodyRevision(Long.MAX_VALUE).build(),
                exchange().setOffer(offer().setSourceRevision(Long.MAX_VALUE)).build(),
                exchange().setOffer(offer().setValidUntil(time(2000)).setMaxAbsorptionPerSecond(Double.MAX_VALUE))
                        .setEndedAt(time(2000)).build(),
                exchange().setOffer(offer().setAvailableQi(inventory(BodyMessages.QiPhase.FREE, "meridian:none", 1e100)))
                        .build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.encodeExchange(invalid));
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.decodeExchange(invalid.toByteArray()));
        }

        // 明确正供给区间的右端允许作为结束点，起点可以位于窗口内部。
        EnvironmentQiExchange partial = exchange().setStartedAt(time(500))
                .setAbsorbedQi(inventory(BodyMessages.QiPhase.FREE, "meridian:none", 1)).build();
        assertEquals(partial, EnvironmentProtobuf.decodeExchange(EnvironmentProtobuf.encodeExchange(partial)));
    }

    @Test
    public void receiptsRequireBothAccountRevisionsToAdvanceExactlyOnce() {
        // 成功事实必须绑定原候选版本，不能只递增身体而遗漏环境，也不能溢出或跳号。
        var receipt = EnvironmentQiReceipt.newBuilder().setExchange(exchange())
                .setBodyRevision(1).setSourceRevision(1).build();
        for (EnvironmentQiReceipt invalid : new EnvironmentQiReceipt[] {
                receipt.toBuilder().clearBodyRevision().build(),
                receipt.toBuilder().clearSourceRevision().build(),
                receipt.toBuilder().setBodyRevision(0).build(),
                receipt.toBuilder().setSourceRevision(2).build(),
                receipt.toBuilder().setExchange(exchange().setExpectedBodyRevision(1)).build(),
                receipt.toBuilder().setExchange(exchange().setOffer(offer().setSourceRevision(1))).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.encodeReceipt(invalid));
            assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.decodeReceipt(invalid.toByteArray()));
        }

        // int64 末端的最后一次合法递增仍能精确往返，不通过浮点数保存版本。
        var lastReceipt = receipt.toBuilder().setExchange(exchange().setExpectedBodyRevision(Long.MAX_VALUE - 1)
                        .setOffer(offer().setSourceRevision(Long.MAX_VALUE - 1)))
                .setBodyRevision(Long.MAX_VALUE).setSourceRevision(Long.MAX_VALUE).build();
        assertEquals(lastReceipt, EnvironmentProtobuf.decodeReceipt(EnvironmentProtobuf.encodeReceipt(lastReceipt)));
    }

    private static EnvironmentQiOffer.Builder offer() {
        // 一秒稳定窗口内最多吸收两元，原始来源另有库存，便于分别验证带宽与余额。
        return EnvironmentQiOffer.newBuilder().setProtocolVersion(1).setSourceId("s").setSourceRevision(0)
                .setBodyId("b").setAvailableQi(inventory(BodyMessages.QiPhase.FREE, "meridian:none", 10))
                .setMaxAbsorptionPerSecond(2).setValidFrom(time(0)).setValidUntil(time(1000));
    }

    private static EnvironmentQiExchange.Builder exchange() {
        // 两个方向采用不同种类，确保序列化不会将释放误并入吸收或进行隐式转化。
        return EnvironmentQiExchange.newBuilder().setExchangeId("exchange-1").setOffer(offer())
                .setExpectedBodyRevision(0).setStartedAt(time(0)).setEndedAt(time(1000))
                .setAbsorbedQi(inventory(BodyMessages.QiPhase.FREE, "meridian:none", 2))
                .setReleasedQi(inventory(BodyMessages.QiPhase.SOLID, "addon:lava", 1));
    }

    private static BodyMessages.TimeCursor time(long elapsedMillis) {
        // 所有测试采用同一模拟时钟，只有拒绝场景显式改变域或世代。
        return BodyMessages.TimeCursor.newBuilder().setDomain("test:clock").setEpoch("e")
                .setElapsedMillis(elapsedMillis).build();
    }

    private static BodyMessages.QiInventory inventory(BodyMessages.QiPhase phase, String attribute, double amount) {
        // 夹具使用线协议构造明细，使非法消息能够直接进入边界校验。
        return BodyMessages.QiInventory.newBuilder().addEntries(BodyMessages.Qi.newBuilder()
                .setDensity(QiDensity.baseline(phase)).setAttribute(attribute).setAmount(amount)).build();
    }
}
