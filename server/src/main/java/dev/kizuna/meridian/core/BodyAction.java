package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.HashSet;
import java.util.List;

/** 附属动作定义及运行实例；占用声明与实际做功费用分别记录。 */
public final class BodyAction {
    private BodyAction() {
        // 内容定义和实例都是不可变数据，变更入口由 BodyActions 统一执行。
    }

    public record Definition(DefinitionId id, int version, String displayName,
                             List<Anatomy.TissueId> tissueIds, List<Anatomy.OrganId> organIds,
                             List<Anatomy.MeridianId> guidedMeridianIds) {
        public Definition(DefinitionId id, int version, String displayName,
                          List<Anatomy.TissueId> tissueIds, List<Anatomy.OrganId> organIds) {
            // 不接管真元流向的动作仅声明既有组织和器官占用。
            this(id, version, displayName, tissueIds, organIds, List.of());
        }
        public Definition {
            // 定义可表示任何物种的动作，具体局部模块在启动时与实际身体核对。
            MeridianErrors.required(id, Data.ACTION_REQUIRED);
            DataChecks.text(displayName, "动作名称");
            if (version <= 0) {
                throw MeridianErrors.invalid(Data.ACTION_VERSION);
            }
            tissueIds = List.copyOf(tissueIds);
            organIds = List.copyOf(organIds);
            guidedMeridianIds = List.copyOf(guidedMeridianIds);
            if (new HashSet<>(tissueIds).size() != tissueIds.size()
                    || new HashSet<>(organIds).size() != organIds.size()
                    || new HashSet<>(guidedMeridianIds).size() != guidedMeridianIds.size()) {
                throw MeridianErrors.invalid(Data.DUPLICATE_ACTION_USE);
            }
        }
    }

    public record Active(String instanceId, Definition definition, long startedMillis) {
        public Active {
            // 两个调用方可以执行相同定义，但须用不同实例身份分别结束自己的动作。
            DataChecks.localId(instanceId);
            MeridianErrors.required(definition, Data.ACTION_REQUIRED);
            if (startedMillis < 0) {
                throw MeridianErrors.invalid(Data.INVALID_ACTION_STATE);
            }
        }
    }
}
