package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;

import java.util.Optional;

/** 宿主提供的权威快照读取入口；查询不得推进时间、补充资源或创建身体。 */
@FunctionalInterface
public interface BodySnapshotReader {
    /** 返回指定身体的不可变快照；不存在用 empty 表示，不返回 null 或其他身体。 */
    Optional<BodySnapshot> find(BodyId bodyId);
}
