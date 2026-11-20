package dev.kizuna.meridian.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CaptureBudget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CaptureBudgetState;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.HashMap;
import java.util.Map;

/** 接触摄入限额随唯一身体保存，重连和切换吐纳方式都不能重置当秒用量。 */
final class QiCaptureBudget {
    private static final String KEY = "meridian:capture_budget";
    private static final int SCHEMA_VERSION = 1;
    private static final long WINDOW_MILLIS = 1_000;

    private QiCaptureBudget() {
        // 这里只记当秒已接收剂量，真元仍只存在身体和环境的实际账户。
    }

    static double available(BodySnapshot body, Anatomy.OrganId organId, double perSecond) {
        // 当前器官倍率同时放大采样和每秒上限，同一窗口内的多次命中共用余额。
        CaptureBudget budget = read(body).getOrgansOrDefault(organId.value(), CaptureBudget.getDefaultInstance());
        double consumed = consumed(body, budget);
        if (!Double.isFinite(perSecond)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }
        return Math.max(0, perSecond - consumed);
    }

    static Map<String, Any> consume(BodySnapshot body, Anatomy.OrganId organId, double amount) {
        // 仅在身体与环境候选都成功时随事务发布，新秒覆盖旧窗口而不积攒额度。
        CaptureBudgetState previous = read(body);
        CaptureBudget old = previous.getOrgansOrDefault(organId.value(), CaptureBudget.getDefaultInstance());
        CaptureBudget budget = CaptureBudget.newBuilder()
                .setWindowMillis(window(body))
                .setConsumedQi(consumed(body, old) + amount)
                .build();
        CaptureBudgetState updated = previous.toBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .putOrgans(organId.value(), budget)
                .build();
        var progress = new HashMap<>(body.addonProgress());
        progress.put(KEY, Any.pack(updated));
        return Map.copyOf(progress);
    }

    private static double consumed(BodySnapshot body, CaptureBudget budget) {
        // 已跨秒的旧记录不参与当前秒的限额。
        if (budget.getWindowMillis() != window(body)) {
            return 0;
        }
        return budget.getConsumedQi();
    }

    private static long window(BodySnapshot body) {
        // 身体模拟时钟保证离线和存档恢复后仍使用相同时间基准。
        return body.settledAt().elapsedMillis() / WINDOW_MILLIS * WINDOW_MILLIS;
    }

    private static CaptureBudgetState read(BodySnapshot body) {
        // 无历史摄入时余额全新，损坏或未知版本必须拒绝，不能重置后继续吸收。
        Any payload = body.addonProgress().get(KEY);
        if (payload == null) {
            return CaptureBudgetState.getDefaultInstance();
        }
        try {
            CaptureBudgetState state = payload.unpack(CaptureBudgetState.class);
            if (state.getSchemaVersion() != SCHEMA_VERSION) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ADDON_PROGRESS);
            }
            for (CaptureBudget budget : state.getOrgansMap().values()) {
                if (budget.getWindowMillis() < 0 || !Double.isFinite(budget.getConsumedQi())
                        || budget.getConsumedQi() < 0) {
                    throw MeridianErrors.invalid(MeridianErrors.INVALID_ADDON_PROGRESS);
                }
            }
            return state;
        } catch (InvalidProtocolBufferException failure) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ADDON_PROGRESS);
        }
    }
}
