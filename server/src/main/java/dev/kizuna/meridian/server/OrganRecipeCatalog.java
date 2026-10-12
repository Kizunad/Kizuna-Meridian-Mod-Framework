package dev.kizuna.meridian.server;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 宿主安装的不可变配方目录；官方和第三方附属包提交相同的 protoc 消息。 */
public final class OrganRecipeCatalog {
    private final Map<Key, BodyMessages.OrganRecipe> recipes;

    public OrganRecipeCatalog(List<BodyMessages.OrganRecipe> definitions) {
        // 先验证全部候选再发布，重复 ID+版本导致整份目录失败，不能覆盖旧定义。
        var validated = new HashMap<Key, BodyMessages.OrganRecipe>();
        for (var definition : List.copyOf(definitions)) {
            CapabilityProtobuf.fromRecipe(definition);
            var key = new Key(definition.getId(), definition.getVersion());
            if (validated.putIfAbsent(key, definition) != null) {
                throw MeridianErrors.invalid(Data.DUPLICATE_RECIPE);
            }
        }
        recipes = Map.copyOf(validated);
    }

    public Optional<BodyMessages.OrganRecipe> find(String id, int version) {
        // 客户端只能引用已安装版本；知识、武器等宿主条件另由授权策略检查。
        return Optional.ofNullable(recipes.get(new Key(id, version)));
    }

    private record Key(String id, int version) { }
}
