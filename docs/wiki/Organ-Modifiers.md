# 组织与器官的临时能力修正

`body.proto` 的 `CapabilityModifier.target` 在 `tissue_id`（原字段 3）与 `organ_id`（字段 11）间二选一。
器官修正使用 optional `intake_peak`（12）和 `throughput_peak`（13）；缺省均为 1。
旧组织快照可继续读取。组织目标的器官峰值必须为 1，器官目标的组织峰值必须为 1；未知目标拒绝。

两类修正共用每身体 32 条上限、来源身份、曲线边界、倍率夹取与死亡清理。
倍率为 `1 + (peak - 1) × curve_value`；同维度多个来源相乘后夹取。
曲线允许负值表达虚脱，任何节点都不能越过最低有效倍率。
到期清除；`LIFESPAN_ONLY` 也不延长曲线，附属要暂停效果时须通过可信活动重排自己的时间计划。

`OrganCapabilities` 读取实际构型与当前器官修正，不包含人体 ID 或药水规则。
持续摄入按区间有效摄入速率的积分校验；接触摄入同时使用采样倍率与当秒额度，详见[吐纳](Gathering.md)。
器官端口吞吐读有效 `throughput_per_second`。变化影响新摄入与新运输，既有在途保留其已结算参数。
组织的 `tempoMultiplierAt` 用于宿主按模拟时刻投影动作速度，`enduranceMultiplier` 只返回曲线倍率；
需要 HP、疲劳和训练成长的调用方须明确按自身公式组合，不能重复乘伤势。

加撤修正均经 `BodyActivity`。撤销必须属于可信调用来源，和账户变化、伤害、进度更新一并校验和提交。
不另建器官修正存档或数量池。跨附属入口见[宿主接入](Host-Integration.md)。
