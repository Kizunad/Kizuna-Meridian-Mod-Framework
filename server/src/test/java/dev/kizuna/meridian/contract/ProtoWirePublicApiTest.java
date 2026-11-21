package dev.kizuna.meridian.contract;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.protocol.ProtoWire;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ProtoWirePublicApiTest {
    @Test
    public void publicMethodsRoundTripAndRejectUnknownFields() {
        // 从 protocol 包外调用公共入口，确保别仓可以复用同一有界校验。
        Qi qi = Qi.newBuilder()
                .setAttribute("minecraft:water")
                .setAmount(1)
                .setDensity(1)
                .build();
        QiInventory message = QiInventory.newBuilder().addEntries(qi).build();
        ProtoWire.validate(message);
        byte[] encoded = ProtoWire.write(message);
        assertEquals(message, ProtoWire.read(encoded, QiInventory.parser()));

        UnknownFieldSet.Field unknownValue = UnknownFieldSet.Field.newBuilder().addVarint(1).build();
        UnknownFieldSet unknownFields = UnknownFieldSet.newBuilder().addField(1000, unknownValue).build();
        QiInventory invalid = message.toBuilder().setUnknownFields(unknownFields).build();
        assertThrows(IllegalArgumentException.class, () -> ProtoWire.read(invalid.toByteArray(), QiInventory.parser()));
    }
}
