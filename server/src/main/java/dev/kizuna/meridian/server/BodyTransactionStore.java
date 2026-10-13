package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;

import java.util.Optional;
import java.util.function.Function;

/** 宿主内部事务适配；数据库类型不进入公开契约，跨语言记录采用 Protobuf。 */
public interface BodyTransactionStore {
    /**
     * 在同一具身体的串行事务内恰好调用 work 一次；不能在冲突后自动重复回调。
     * 读取、策略执行、暂存、提交期间身体与回执必须隔离于其他写入。
     * 回调正常返回才原子发布暂存的身体、回执和环境释放，抛异常则全部回滚。
     * 本方法成功返回表示提交已完成；连接故障等导致结果不确定时，调用者用原 ID 重试。
     */
    <T> T transact(BodyId bodyId, Function<Transaction, T> work);

    interface Transaction {
        /** 不存在返回 empty，不自动创建身体；返回快照身份必须等于事务目标。 */
        Optional<BodySnapshot> snapshot();

        /** 只查当前身体内的可信调用者与请求 ID，回执须与身体一起保存和恢复。 */
        Optional<CommandReceipt> receipt(String callerId, String requestId);

        /** 返回并隔离真实来源及共享供给账；跨身体读取同一来源也必须串行或在提交时检查冲突。 */
        default Optional<EnvironmentQiSource> environmentSource(String sourceId) {
            // 未接入环境存储的适配器明确没有来源，不能用空账或客户端 offer 兜底。
            return Optional.empty();
        }

        /**
         * 一次事务至多暂存一次。校验当前 revision、目标身份和回执唯一键后一起写入。
         * 时间命令须校验计划、前后身体与释放明细一致；加工损耗或死亡 released_qi 均转入环境账户，
         * 并将实体死亡等后续动作的可靠记录纳入同一提交边界。不得靠审计通知完成资源转移。
         * 运输命令须校验源扣除与在途货物一致，不得先扣源再用另一笔写入创建在途。
         * 加工启动须验证缓存扣除与固定配方/输入账户；做功须验证对应组织能力和疲劳变化。
         * 摄入须重算器官与时间变化、真实来源扣账及共享区间预算；身体、来源和两类回执同时发布。
         * organ_intake 的 released_qi 只入选定来源，不得再入普通时间命令的默认释放账户。
         * 暂存并非已经提交；回调后续失败时不能保留任一项。不得从回调泄露事务对象。
         */
        void stage(BodySnapshot next, CommandReceipt receipt);
    }
}
