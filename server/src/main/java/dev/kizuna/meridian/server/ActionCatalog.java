package dev.kizuna.meridian.server;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 附属动作的不可变目录，启动请求只能引用其中已登记的定义。 */
public final class ActionCatalog {
    private final Map<Key, BodyMessages.ActionDefinition> definitions;

    public ActionCatalog(List<BodyMessages.ActionDefinition> actions) {
        // 整批校验后发布，重复修订不能用加载顺序覆盖。
        var validated = new HashMap<Key, BodyMessages.ActionDefinition>();
        for (BodyMessages.ActionDefinition action : List.copyOf(actions)) {
            ActionProtobuf.fromDefinition(action);
            if (validated.putIfAbsent(new Key(action.getId(), action.getVersion()), action) != null) {
                throw MeridianErrors.invalid(MeridianErrors.Data.DUPLICATE_ACTION);
            }
        }
        definitions = Map.copyOf(validated);
    }

    public Optional<BodyMessages.ActionDefinition> find(String id, int version) {
        // 缺失返回空，不用最新定义替代旧修订；停止已有实例不需要查询目录。
        return Optional.ofNullable(definitions.get(new Key(id, version)));
    }

    private record Key(String id, int version) { }
}
