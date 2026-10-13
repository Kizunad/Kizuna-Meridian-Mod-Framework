package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.EnvironmentConfigProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.BlockQiTransmission;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiExchange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiOffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EnvironmentConfigurationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bundledDefaultsAndFileOverridesProducePortableConfig() throws Exception {
        // 保护内置配置与读取入口：文件只写覆盖项，默认空气和未列出方块仍可用于实际计算。
        EnvironmentQiConfig defaults = EnvironmentConfigLoader.defaults();
        assertEquals(defaults, EnvironmentConfigLoader.parse(""));
        assertEquals(1, defaults.getRegionSizeChunks());
        assertEquals(1, defaults.getAbsorptionVolumeBlocksPerSecond(), 0);
        assertEquals(1, defaults.getDiffusionSpeedBlocksPerSecond(), 0);
        Set<String> airIds = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
        for (var block : defaults.getBlockTransmissionsList()) {
            assertTrue(block.getTransmission() >= 0);
            if (airIds.contains(block.getBlockId())) {
                assertEquals(1, block.getTransmission(), 0);
            } else {
                assertTrue(block.getBlockId(), block.getTransmission() < 1);
            }
        }

        var path = temporaryFolder.newFile("environment.toml").toPath();
        Files.writeString(path, """
                [region]
                size_chunks = 2
                [absorption]
                volume_blocks_per_second = 2
                [diffusion.blocks]
                "addon.test:qi_conduit" = 2.5
                "minecraft:stone" = 0.2
                "addon:sealed" = 0
                """, StandardCharsets.UTF_8);
        EnvironmentQiConfig config = EnvironmentConfigLoader.load(path);
        byte[] payload = EnvironmentConfigProtobuf.encode(config);
        assertEquals(config, EnvironmentConfigProtobuf.decode(payload));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(payload));

        var rules = new EnvironmentQiRules(config);
        assertEquals(2.5, rules.blockTransmission("addon.test:qi_conduit"), 0);
        assertEquals(0.2, rules.blockTransmission("minecraft:stone"), 0);
        assertEquals(0.6, rules.blockTransmission("minecraft:water"), 0);
        assertEquals(0.1, rules.blockTransmission("minecraft:unlisted_block"), 0);
        assertEquals(0.1, rules.blockTransmission("addon:unlisted_block"), 0);
        assertEquals(0, rules.blockTransmission("addon:sealed"), 0);
        assertEquals(1, rules.blockTransmission("minecraft:void_air"), 0);
        assertEquals(0.95, rules.blockTransmission("minecraft:structure_void"), 0);
    }

    @Test
    public void malformedFilesFailInsteadOfSilentlyFallingBack() throws Exception {
        // 拼错字段、重复键、类型错误与非法数值必须阻止安装配置，不能沿用看似有效的默认值。
        for (String invalid : new String[] {
                "config_version = 3", "config_version = 2\nconfig_version = 2", "[broken",
                "[regions]\nsize_chunks = 2", "[region]\nszie_chunks = 2", "region = 2",
                "[region]\nsize_chunks = 0", "[region]\nsize_chunks = -1", "[region]\nsize_chunks = 1.5",
                "[region]\nsize_chunks = 2147483648", "[region]\nsize_chunks = '2'",
                "[absorption]\nvolume_blocks_per_second = true",
                "[absorption]\nvolume_blocks_per_second = nan",
                "[diffusion]\nspeed_blocks_per_second = inf",
                "[diffusion]\ndefault_block_transmission = 1",
                "[diffusion]\nblocks = []",
                "[diffusion.blocks]\n\"minecraft:air\" = 0.9",
                "[diffusion.blocks]\n\"minecraft:void_air\" = 2",
                "[diffusion.blocks]\n\"addon:conduit\" = -0.1",
                "[diffusion.blocks]\n\"Bad Id\" = 0.5",
                "#".repeat(EnvironmentConfigLoader.MAX_CONFIG_BYTES + 1)
        }) {
            assertThrows(invalid.substring(0, Math.min(invalid.length(), 100)), IllegalArgumentException.class,
                    () -> EnvironmentConfigLoader.parse(invalid));
        }
        var missing = temporaryFolder.getRoot().toPath().resolve("missing.toml");
        assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigLoader.load(missing));
        var oversized = temporaryFolder.newFile("oversized.toml").toPath();
        Files.writeString(oversized, "#".repeat(EnvironmentConfigLoader.MAX_CONFIG_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigLoader.load(oversized));
    }

    @Test
    public void protobufValidationAlsoProtectsNonJavaHosts() {
        // 外部宿主可直接发送配置消息，同样要有完整字段和唯一方块；合法的零速率仍保留存在性。
        EnvironmentQiConfig config = EnvironmentConfigLoader.defaults();
        for (EnvironmentQiConfig invalid : new EnvironmentQiConfig[] {
                config.toBuilder().setConfigVersion(0).build(),
                config.toBuilder().clearRegionSizeChunks().build(),
                config.toBuilder().setRegionSizeChunks(0).build(),
                config.toBuilder().clearAbsorptionVolumeBlocksPerSecond().build(),
                config.toBuilder().setAbsorptionVolumeBlocksPerSecond(Double.NaN).build(),
                config.toBuilder().clearDiffusionSpeedBlocksPerSecond().build(),
                config.toBuilder().setDiffusionSpeedBlocksPerSecond(-1).build(),
                config.toBuilder().clearDefaultBlockTransmission().build(),
                config.toBuilder().setDefaultBlockTransmission(1).build(),
                config.toBuilder().clearBlockTransmissions().build(),
                config.toBuilder().addBlockTransmissions(config.getBlockTransmissions(0)).build(),
                config.toBuilder().addBlockTransmissions(BlockQiTransmission.newBuilder()
                        .setBlockId("addon:conduit")).build(),
                config.toBuilder().addBlockTransmissions(BlockQiTransmission.newBuilder()
                        .setBlockId("addon:conduit").setTransmission(Double.POSITIVE_INFINITY)).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigProtobuf.encode(invalid));
            assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigProtobuf.decode(invalid.toByteArray()));
        }
        EnvironmentQiConfig stopped = config.toBuilder().setAbsorptionVolumeBlocksPerSecond(0)
                .setDiffusionSpeedBlocksPerSecond(0).build();
        assertEquals(stopped, EnvironmentConfigProtobuf.decode(EnvironmentConfigProtobuf.encode(stopped)));
        var rules = new EnvironmentQiRules(stopped);
        assertEquals(0, rules.absorptionLimitPerSecond(inventory(10), 1), 0);
        assertEquals(0, rules.candidateDiffusionPerSecond(10, 1, 5, 2.5), 0);
    }

    @Test
    public void regionMappingAndConcentrationFeedExistingExchangeBudget() {
        // 两 chunk 边长覆盖 32×32 格；负坐标归区和体积共同决定密度，不能只按库存给固定速率。
        EnvironmentQiConfig config = EnvironmentConfigLoader.parse("[region]\nsize_chunks = 2");
        var rules = new EnvironmentQiRules(config);
        assertEquals(-2, rules.regionCoordinate(-3));
        assertEquals(-1, rules.regionCoordinate(-2));
        assertEquals(-1, rules.regionCoordinate(-1));
        assertEquals(0, rules.regionCoordinate(0));
        assertEquals(0, rules.regionCoordinate(1));
        assertEquals(1, rules.regionCoordinate(2));
        double volume = rules.regionVolumeBlocks(384);
        assertEquals(393216, volume, 0);
        QiInventory inventory = inventory(volume * 2);
        assertEquals(2, rules.concentration(inventory, volume), 0);
        assertEquals(2, rules.absorptionLimitPerSecond(inventory, volume), 0);
        assertEquals(1, rules.absorptionLimitPerSecond(inventory, volume * 2), 0);

        // 同一浓度派生的速率直接进入现有 offer；浓度减半后原先两元的交换就必须被拒绝。
        var start = BodyMessages.TimeCursor.newBuilder().setDomain("test:clock").setEpoch("e").setElapsedMillis(0);
        var end = start.clone().setElapsedMillis(1000);
        var available = BodyMessages.QiInventory.newBuilder().addEntries(BodyMessages.Qi.newBuilder()
                .setPhase(BodyMessages.QiPhase.FREE).setAttribute("meridian:none").setAmount(10));
        var offer = EnvironmentQiOffer.newBuilder().setProtocolVersion(1).setSourceId("test:region")
                .setSourceRevision(0).setBodyId("body").setAvailableQi(available)
                .setMaxAbsorptionPerSecond(rules.absorptionLimitPerSecond(inventory, volume))
                .setValidFrom(start).setValidUntil(end);
        var exchange = EnvironmentQiExchange.newBuilder().setExchangeId("absorb").setOffer(offer)
                .setExpectedBodyRevision(0).setStartedAt(start).setEndedAt(end)
                .setAbsorbedQi(available.clone().setEntries(0, available.getEntries(0).toBuilder().setAmount(2)))
                .setReleasedQi(BodyMessages.QiInventory.getDefaultInstance());
        EnvironmentProtobuf.validateExchange(exchange.build());
        exchange.setOffer(offer.setMaxAbsorptionPerSecond(rules.absorptionLimitPerSecond(inventory, volume * 2)));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.validateExchange(exchange.build()));
        assertEquals(0, rules.absorptionLimitPerSecond(QiInventory.empty(), volume), 0);
        assertThrows(IllegalArgumentException.class, () -> rules.regionVolumeBlocks(0));
        assertThrows(IllegalArgumentException.class, () -> rules.concentration(inventory, 0));
        assertThrows(IllegalArgumentException.class, () -> rules.concentration(inventory, Double.NaN));
    }

    @Test
    public void transmissionRespectsBarriersDirectionAndEnhancedBlocks() {
        // 接触面两侧共同决定传播；高导率不截为 1，屏障仍能阻断，低浓度不能向高浓度自发输送。
        var rules = new EnvironmentQiRules(EnvironmentConfigLoader.parse("""
                [diffusion.blocks]
                "addon:conduit" = 2.5
                "addon:huge" = 1.7976931348623157e308
                """));
        assertEquals(1, rules.contactTransmission("minecraft:air", "minecraft:void_air"), 0);
        assertEquals(0, rules.contactTransmission("addon:conduit", "minecraft:barrier"), 0);
        assertEquals(0.1, rules.contactTransmission("minecraft:stone", "minecraft:stone"), 0);
        assertEquals(2.5, rules.contactTransmission("addon:conduit", "addon:conduit"), 0);
        assertEquals(1.0 / 5.5, rules.contactTransmission("minecraft:air", "minecraft:stone"), 1e-15);
        assertEquals(Double.MAX_VALUE, rules.contactTransmission("addon:huge", "addon:huge"), 0);
        assertEquals(10, rules.candidateDiffusionPerSecond(2, 1, 10, 1), 0);
        assertEquals(25, rules.candidateDiffusionPerSecond(2, 1, 10, 2.5), 0);
        assertEquals(0, rules.candidateDiffusionPerSecond(1, 2, 10, 1), 0);
        assertEquals(0, rules.candidateDiffusionPerSecond(2, 2, 10, 1), 0);
        assertEquals(0, rules.candidateDiffusionPerSecond(2, 1, 10, 0), 0);
        assertThrows(IllegalArgumentException.class, () -> rules.candidateDiffusionPerSecond(2, 1, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> rules.candidateDiffusionPerSecond(2, 1, 10, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> rules.candidateDiffusionPerSecond(Double.MAX_VALUE, 0, 10, 1));
    }

    private static QiInventory inventory(double amount) {
        // 用真实账户构造浓度，测试不会再单独保存一份可能与库存脱节的浓度状态。
        return new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, amount)));
    }
}
