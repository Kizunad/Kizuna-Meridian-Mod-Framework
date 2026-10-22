# 单脉开拓执行与附属策略

状态：已实现规则登记、开始/暂停命令、任务存档、沿拓扑引导、连续适应、独立费用和完成扩容。**启动与维持条件由生物或玩法附属裁定**，Framework 只执行通用计划并维护唯一权威账本。任务期间容量始终是稳定结构容量；只有成功开拓才增加内腔体积。境界相变、外壁储备突破和材料消耗尚未实现。

## 规则与职责

规则和任务定义在 `body.proto`，玩家意图在 `opening.proto`，可信策略通信在 `opening_policy.proto`。Java 值为 `MeridianOpeningRule` 和 `OpeningTask`，通过 `OpeningProtobuf` 映射。

| 规则字段 | 通用用途 |
|---|---|
| `id / version / display_name` | 命名空间身份、精确正整数修订与名称 |
| `target_meridian_id` | 单条目标经脉的身体局部 ID |
| `participants.volume` | 引导参与脉的占用目标体积；不扩大稳定容量 |
| `action_id / action_version` | 可信目录中的动作，声明占用组织与器官 |
| `efforts` | 占用模块的独立持续功率 W |
| `continuous_hold` | 条件中断是否清零本轮适应计时 |
| `lumen_volume_gain` | 成功后一次性增加稳定内腔体积，不产生真元 |
| `guidance_qi_per_second / guidance_batch_qi` | 引导速率和最大批次，仍受真实通路吞吐约束 |
| `hold_qi_per_second` | 维持期间的真元扣费速率，按全身内腔实际占用体积分摊 |
| `policy_id = 22 / policy_config = 23` | 附属策略身份和 `google.protobuf.Any` 配置；核心不解释内容条件 |

旧条件字段 `5、7、8、11、12、14、19` 及名称均已 reserved；前置脉、承受度要求、全身比例、参与组选择、摄入要求、严格门槛和时长公式归附属自己的 Protobuf。Human 使用自己的 `HumanOpeningConditions`，不存在默认人体策略。

引导目标只声明期望占用体积，不持有库存。真实内腔、外壁、器官缓存及在途各有唯一所有者；目标体积、门槛和收据不会增加容量或真元。

## 可信策略契约

`OpeningPolicyRequest` 包含协议版本、不可变身体快照、固定规则、阶段及检查区间。策略只能由可信宿主调用，玩家不能上传策略、裁定或成功结果。

- `PREPARE_OPENING`：附属检查启动条件，返回引导目标体积、所需模拟毫秒、开拓后的稳定容量、需要观察的摄入器官及收据保存窗口。
- `CHECK_OPENING`：附属判断当前条件及整个待结算区间是否成立，返回 `ready` 和可选的正数 `next_check_delay_millis`。有持续扣费时须用旧余额投影区间费用；同毫秒到货不能追认此前的连续适应。
- `OpeningPolicyDecision.rejection`：零表示没有拒绝，其余复用公开 `FailureCode`。

核心复验目标、参与组、模块引用、动作占用、有限数值与时长，并要求计划的稳定容量等于当前稳定容量加规则增量。观察器官必须属于动作且支持摄入。附属没有 Store 或可写身体引用，不能借策略直接修改账户。最终动作、费用、运输、成长和释放仍在权威事务内提交。

Java 参考实现通过 `OpeningPolicy` SPI 与 `ServiceLoader` 发现执行器，登记文件为 `META-INF/services/dev.kizuna.meridian.core.OpeningPolicy`，策略 ID 不可重复。适配器也可转发相同 Protobuf 到其他语言；其他语言宿主须实现对应策略，安装 Java JAR 不自动获得跨语言执行能力。

新启动缺少执行器返回 `OPENING_POLICY_UNAVAILABLE = 45`。已保存的活动任务缺少策略时安全暂停并解除动作占用，保留合法的身体容量和真元余额；保存原规则与进度事实，不套用其他生物规则。数据解码只做结构一致性校验，不要求策略已安装，也不推进身体。

## 意图、授权与事务

`MeridianOpeningRequest` 提供 `protocol_version = 1`、请求 ID、身体 ID、显式 `expected_revision`，以及开始或暂停操作：

| 操作 | 客户端字段 |
|---|---|
| `StartMeridianOpening` | 可信目录中的 `rule_id / rule_version` |
| `PauseMeridianOpening` | 当前身体局部 `meridian_id` |

客户端不提交时间、进度、摄入证据、消耗、临时额度或完成标记。`BodyCommand.start_meridian_opening = 19` 和 `pause_meridian_opening = 20` 复用授权、私有策略、版本和幂等检查。`BodyCommandHandler` 从 `AddonRegistry.openings()` 解析规则；`CommandReceipt.opening = 9` 保存启停结果及释放明细。Store 从旧身体复算，身体、回执和环境释放一起提交。

`OpeningSession(context, bodyId, commands, bodies, access)` 提供 Java 连接入口：检查绑定身体及权限 → 检查原始请求重试 → 检查原始 revision → 按可信时钟结时 → 基于结时后版本执行意图。不能先替换客户端版本再检查。

会话缓存最近 64 个原始意图和响应，同 ID 同意图返回原响应，不同意图返回 `REQUEST_ID_CONFLICT`，重试仍重新授权。缓存不持久化，重连或淘汰后的旧请求按版本拒绝。成功回执继续由 Store 保存。

## 事件执行与守恒

开始时核心检查身体存活、目标存在且未完成、无并发开脉、动作引用有效，再调用附属启动策略。每具身体同时运行一个开脉任务；已完成目标拒绝重复领取容量成长。

时间按运输到达、加工完成、策略检查、预算耗尽、适应完成和寿尽边界推进。旧区间先支付费用，策略确认整段连续满足才积累时间；条件中断按 `continuous_hold` 清零或保留进度。死亡优先于同毫秒完成。渲染帧只插值，不参与计时或扣账。

任务固定完整规则、策略配置、引导目标、时长、成功后的目标稳定容量、进度和真实摄入收据。收据仅来自成功提交的摄入，不是额外资源。普通 `stop_action` 不能绕过任务清理。冻结暂停本次尝试，不补离线活动时间。

引导期间接管自然扩散方向，反向广度优先搜索选择到目标的下一跳；逐段源扣账、在途、到货。尚未开拓的经脉可以参与专用引导，普通主动运输仍按开拓权限校验。肺等具体器官不属于核心特殊分支。

目标引导补到策略返回的占用体积，实际容量检查仍只读取稳定结构体积；已有入向预约先到货，其余参与脉保留中转批次空间。所有出口与普通运输共享实际端口吞吐。

器官缓存有待输出真元且直接连接完整经脉时，引导为该经脉让出至多一半吞吐（不超过器官吞吐），已有器官输入批次抵扣这份预留。另一半仍可将满载经脉中的真元向目标搬运，腾出收货空间；缓存排空后解除预留。预留只影响引导调度，不增加实际吞吐、容量或余额，避免每次到货后引导立即抢满入口，令持续摄入永久等待。

器官与组织费用按独立疲劳预算结算；其他动作仍占用模块时不进行空闲恢复。准备阶段支付活动费用，`HOLDING` 区间按规则扣真元并原子记入环境释放；Human 规则按全身各内腔的实际占用体积分摊。条件不满足不能凭累计填充量完成。

完成时一次增加稳定内腔体积并设置 `OPENED`，随后解除动作占用。暂停、耗尽、失能或策略缺失只结束任务，不改变稳定容量或合法库存；在途继续保存，到货空间不足则阻塞。死亡走全账户释放链。

## 展示与附属注册

`BodyViewUpdate.opening = 4` 提供可信规则选项，存在即替换、空表撤销、省略保留。`BodyViewUpdate.operations = 5` 可携带与身体同 revision 的 `MeridianOperationUpdate`。**开脉条件和进度卡由对应内容附属生成**，UI 只校验、绘制和发送意图；`enabled` 不代替服务端授权。模型、颜色、窗口和光迹归 UI 及生物展示资源。

`AddonBundle.opening_rules = 7` 登记规则，`ContentDefinitionKind.MERIDIAN_OPENING_RULE = 4` 支持精确引用。注册校验命名空间、修订及动作精确依赖；不解释附属 Any。内容若依赖特定生物构型，作者须显式声明依赖。

宿主能力 `meridian:opening_definitions_v1` 只承诺登记定义；`meridian:opening_runtime_v1` 承诺通用执行；器官独立耐力另要求 `meridian:organ_endurance_v1`。执行仍需与 `policy_id` 对应的可信策略。目录缺规则时新启动返回 `OPENING_RULE_NOT_FOUND`；已有任务可在原策略仍可用时继续，原策略缺失则安全暂停。

整体回归覆盖非人环路、真实运输、费用、连续条件、分段与保存恢复、缺策略与非法计划、原始版本、幂等重试、释放及死亡；Human 另验证具体人体门槛和展示卡。境界突破与材料改造属于独立后续能力。
