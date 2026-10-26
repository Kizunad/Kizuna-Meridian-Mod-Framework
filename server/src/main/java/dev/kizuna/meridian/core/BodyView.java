package dev.kizuna.meridian.core;

import java.util.List;

/** 核心算子共用的只读输入；推进候选只在事务结束时转成经过全量校验的快照。 */
interface BodyView {
    BodyStructure structure();
    List<AnatomyState.Part> partStates();
    List<AnatomyState.Tissue> tissueStates();
    List<AnatomyState.Organ> organStates();
    List<MeridianState> meridianStates();
    List<QiTransit> transits();
    VitalityState vitality();
    TimeCursor settledAt();
    long revision();
    List<BodyAction.Active> actions();
    OpeningTask openingTask();
}
