package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.EnvironmentConfigProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;

import java.util.HashMap;
import java.util.Map;

/** 宿主侧环境参数和纯速率计算，不依赖 Minecraft 注册表或世界实现。 */
public final class EnvironmentQiRules {
    private final EnvironmentQiConfig config;
    private final Map<String, Double> blockTransmissions;

    public EnvironmentQiRules(EnvironmentQiConfig config) {
        // 完整校验后缓存只读索引，边界方块查询无需反复扫描整个配置表。
        EnvironmentConfigProtobuf.validate(config);
        this.config = config;
        Map<String, Double> transmissions = new HashMap<>();
        for (var block : config.getBlockTransmissionsList()) {
            transmissions.put(block.getBlockId(), block.getTransmission());
        }
        this.blockTransmissions = Map.copyOf(transmissions);
    }

    public int regionCoordinate(int chunkCoordinate) {
        // 向下取整保持原点两侧的分区连续：N=2 时 chunk -1 属于区域 -1。
        return Math.floorDiv(chunkCoordinate, config.getRegionSizeChunks());
    }

    public double regionVolumeBlocks(int heightBlocks) {
        // chunk 只定义横向 16×16，世界高度由宿主提供，不把区域误算为 N³ 个 chunk。
        if (heightBlocks <= 0) {
            throw MeridianErrors.invalid(Data.positiveFinite("区域有效高度"));
        }
        double sideBlocks = 16.0 * config.getRegionSizeChunks();
        return sideBlocks * sideBlocks * heightBlocks;
    }

    public double concentration(QiInventory inventory, double volumeBlocks) {
        // 浓度始终由唯一库存派生；格指体积而非面积，库存变化就会改变读数。
        MeridianErrors.required(inventory, Environment.INVENTORY_REQUIRED);
        requireNonNegative(volumeBlocks, "区域有效体积");
        if (volumeBlocks == 0) {
            throw MeridianErrors.invalid(Data.positiveFinite("区域有效体积"));
        }
        double qiPerBlock = inventory.totalQi() / volumeBlocks;
        requireNonNegative(qiPerBlock, "环境浓度");
        return qiPerBlock;
    }

    public double absorptionLimitPerSecond(QiInventory inventory, double volumeBlocks) {
        // 元/格乘格/秒得到环境侧供给速度；宿主随后再与器官能力及分配预算取较小值。
        double qiPerBlock = concentration(inventory, volumeBlocks);
        double qiPerSecond = qiPerBlock * config.getAbsorptionVolumeBlocksPerSecond();
        requireNonNegative(qiPerSecond, "环境吸收速率");
        return qiPerSecond;
    }

    public double blockTransmission(String blockId) {
        // 查找精确方块 ID，未列出项回落到默认系数；特殊方块的增强值完整保留。
        new DefinitionId(blockId);
        return blockTransmissions.getOrDefault(blockId, config.getDefaultBlockTransmission());
    }

    public double contactTransmission(String firstBlockId, String secondBlockId) {
        // 相邻方块各贡献半段阻力，用调和平均合成；任一端封闭都必须阻断。
        double first = blockTransmission(firstBlockId);
        double second = blockTransmission(secondBlockId);
        double lower = Math.min(first, second);
        double higher = Math.max(first, second);
        if (lower == 0) {
            return 0;
        }
        // 以较小值缩放，避免直接计算 2*a*b 时有限系数的中间乘积溢出。
        return lower / (0.5 + 0.5 * (lower / higher));
    }

    public double candidateDiffusionPerSecond(double sourceQiPerBlock, double targetQiPerBlock,
            double contactAreaBlocks, double transmission) {
        // 只计算由高浓度流向低浓度的候选量，调用方按共享库存和平衡量统一分配。
        requireNonNegative(sourceQiPerBlock, "源区域浓度");
        requireNonNegative(targetQiPerBlock, "目标区域浓度");
        requireNonNegative(contactAreaBlocks, "区域接触面积");
        requireNonNegative(transmission, "边界传播系数");
        if (sourceQiPerBlock <= targetQiPerBlock || contactAreaBlocks == 0
                || transmission == 0 || config.getDiffusionSpeedBlocksPerSecond() == 0) {
            return 0;
        }

        // 边长/秒 × 方块面面积 × 元/方块体积 = 元/秒，系数 1 本身不是固定流量。
        double concentrationDifference = sourceQiPerBlock - targetQiPerBlock;
        double qiPerSecond = concentrationDifference * config.getDiffusionSpeedBlocksPerSecond();
        qiPerSecond *= contactAreaBlocks;
        qiPerSecond *= transmission;
        requireNonNegative(qiPerSecond, "候选环境扩散速率");
        return qiPerSecond;
    }

    private static void requireNonNegative(double value, String label) {
        // NaN、无穷和负值不能成为运行速率；明确拒绝比悄悄截断更容易诊断宿主错误。
        if (!Double.isFinite(value) || value < 0) {
            throw MeridianErrors.invalid(Data.nonNegativeFinite(label));
        }
    }
}
