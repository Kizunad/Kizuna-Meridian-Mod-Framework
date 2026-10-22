package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;

/** 两端共用的只读密度换算；不包含身体执行、资源授权或第二份账本。 */
public final class QiDensity {
    public static final double FREE = 0.01;

    private QiDensity() {
        // 相态和体积都由消息中的密度派生。
    }

    public static QiPhase phase(double density) {
        // 区间左闭右开；拒绝未定义的低密度、无穷和 NaN。
        if (!Double.isFinite(density) || density < FREE) {
            throw new IllegalArgumentException("真元密度必须为有限数且不小于 0.01");
        }
        if (density < 0.1) return QiPhase.FREE;
        if (density < 1) return QiPhase.GAS;
        if (density < 10) return QiPhase.LIQUID;
        if (density < 100) return QiPhase.SOLID;
        if (density < 1000) return QiPhase.ORDERED;
        return QiPhase.COHERENT;
    }

    public static double baseline(QiPhase phase) {
        // 内容生成采用相态基准，库存换算始终读取每条明细的实际密度。
        return switch (phase) {
            case FREE -> FREE;
            case GAS -> 0.1;
            case LIQUID -> 1;
            case SOLID -> 10;
            case ORDERED -> 100;
            case COHERENT -> 1000;
            default -> throw new IllegalArgumentException("真元相态无效");
        };
    }

    public static double clampToPhase(double density, QiPhase phase) {
        // 合并同一相态的浮点结果时，边界附近的舍入不能把明细误分到另一相态。
        double lower = baseline(phase);
        double upper = switch (phase) {
            case FREE -> 0.1;
            case GAS -> 1;
            case LIQUID -> 10;
            case SOLID -> 100;
            case ORDERED -> 1000;
            case COHERENT -> Double.POSITIVE_INFINITY;
            default -> throw new IllegalArgumentException("真元相态无效");
        };
        if (density < lower) {
            return lower;
        }
        if (Double.isFinite(upper) && density >= upper) {
            return Math.nextDown(upper);
        }
        return density;
    }

    public static double volume(QiInventory inventory) {
        // 明细不跨相态合并，体积仍可统一相加。
        return inventory.getEntriesList().stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
    }

    public static double displayedCapacity(double volume, QiInventory inventory) {
        // 混相按总量/总体积得到展示用平均密度，空账户使用游离态基准。
        double occupied = volume(inventory);
        double density = FREE;
        if (occupied > 0) {
            density = inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum() / occupied;
        }
        return volume * density;
    }
}
