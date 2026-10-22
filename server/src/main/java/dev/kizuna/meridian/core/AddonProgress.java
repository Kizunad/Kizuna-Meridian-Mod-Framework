package dev.kizuna.meridian.core;

import com.google.protobuf.Any;
import dev.kizuna.meridian.error.MeridianErrors;

import java.util.HashMap;
import java.util.Map;

/** 附属自行解释的持久进度；框架只负责有界保存与原子替换。 */
public final class AddonProgress {
    private AddonProgress() {
        // 无独立账本，所有进度随身体一起保存。
    }

    public static void validate(Map<String, Any> progress) {
        // 限制附属扩展的总量，避免任意载荷无限膨胀身体存档。
        if (progress.size() > 64) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ADDON_PROGRESS);
        }
        progress.forEach((id, payload) -> validateEntry(id, payload));
    }

    public static void validateEntry(String id, Any payload) {
        // 命名空间隔离进度归属，具体 schema 由拥有该 ID 的可信附属校验。
        new DefinitionId(id);
        if (payload.getTypeUrl().isBlank() || payload.getTypeUrl().length() > 256
                || payload.getValue().isEmpty() || payload.getSerializedSize() > 65_536) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ADDON_PROGRESS);
        }
    }

    public static BodySnapshot replace(BodySnapshot body, String id, Any progress) {
        // 只改指定附属的一项进度与 revision，保持资源、时间和生命状态完全一致。
        validateEntry(id, progress);
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.invalid(MeridianErrors.Protocol.COMMIT_REVISION);
        }
        var updated = new HashMap<>(body.addonProgress());
        updated.put(id, progress);
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), body.organStates(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(), updated);
    }
}
