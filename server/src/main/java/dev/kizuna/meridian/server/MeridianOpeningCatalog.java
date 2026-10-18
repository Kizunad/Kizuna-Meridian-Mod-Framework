package dev.kizuna.meridian.server;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 附属开拓规则的不可变目录，安装和查询均不推进经脉。 */
public final class MeridianOpeningCatalog {
    private final Map<Key, BodyMessages.MeridianOpeningRule> definitions;

    public MeridianOpeningCatalog(List<BodyMessages.MeridianOpeningRule> rules) {
        // 整批校验后固定定义；包归属、能力和动作引用由 AddonRegistry 校验。
        var validated = new HashMap<Key, BodyMessages.MeridianOpeningRule>();
        for (BodyMessages.MeridianOpeningRule rule : List.copyOf(rules)) {
            OpeningProtobuf.fromRule(rule);
            if (validated.putIfAbsent(new Key(rule.getId(), rule.getVersion()), rule) != null) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.DUPLICATE_RULE);
            }
        }
        definitions = Map.copyOf(validated);
    }

    public Optional<BodyMessages.MeridianOpeningRule> find(String id, int version) {
        // 缺失精确修订返回空，避免悄悄替换已有角色所引用的规则。
        return Optional.ofNullable(definitions.get(new Key(id, version)));
    }

    private record Key(String id, int version) { }
}
