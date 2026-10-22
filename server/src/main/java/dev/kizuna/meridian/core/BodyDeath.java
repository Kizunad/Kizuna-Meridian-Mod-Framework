package dev.kizuna.meridian.core;

import java.util.ArrayList;
import java.util.List;

/** 死亡的通用资源清账；死亡原因由寿命算子或可信附属确定。 */
public final class BodyDeath {
    private BodyDeath() {
        // 只在死亡事件调用，不扫描身体或启动计时任务。
    }

    public record Released(List<MeridianState> meridians, List<AnatomyState.Organ> organs,
                           QiInventory qi) { }

    public static Released release(List<MeridianState> meridians, List<AnatomyState.Organ> organs,
                                   List<QiTransit> transits) {
        // 每种账户只取一次；加工中释放已扣输入，不生成预计输出。
        var inventories = new ArrayList<QiInventory>();
        var clearedMeridians = new ArrayList<MeridianState>();
        for (MeridianState meridian : meridians) {
            inventories.add(meridian.lumenQi());
            inventories.add(meridian.wallQi());
            clearedMeridians.add(new MeridianState(meridian.id(), QiInventory.empty(), QiInventory.empty(),
                    meridian.fatigue(), meridian.condition(), meridian.opening()));
        }
        for (QiTransit transit : transits) {
            inventories.add(transit.cargo());
        }
        var clearedOrgans = new ArrayList<AnatomyState.Organ>();
        for (AnatomyState.Organ organ : organs) {
            inventories.add(organ.buffer());
            if (organ.process() != null) {
                // 加工开始时已经按实际密度扣下原料；死亡释放这笔真实原料，不能重新读取配方模板。
                inventories.add(organ.process().input());
            }
            clearedOrgans.add(new AnatomyState.Organ(organ.id(), organ.hp(), QiInventory.empty(), null, organ.fatigueJoules()));
        }
        return new Released(List.copyOf(clearedMeridians), List.copyOf(clearedOrgans), QiInventory.combine(inventories));
    }
}
