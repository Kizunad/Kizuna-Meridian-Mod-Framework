# 服务端实现与宿主接入

状态：Java 查询、寿元支出/推进、经脉与器官运输、组织能力/做功、器官配方、通用环境摄入与原子交换、宿主事务/策略的内存参考已实现。跨语言查询已完成一次性验证；本次变更的跨语言宿主接入、实际调度/实体死亡、网络与磁盘持久化尚未实现。

目前已提供宿主无关的 [Protobuf v1 数据、查询与变更契约](Protocol-and-Security.md)。Java 消息由 protoc 生成；其他语言使用同一 `.proto` 或构建描述集生成类型，并遵守相同授权、版本、原子性和重试语义。

Java 参考实现和宿主处理器位于 `server/` 子项目，`client/` 目前只生成客户端协议消息；共用 `.proto` 位于 `shared/src/main/proto/`。目录拆分后的产物仍是宿主调用的 Java 库，Fabric 服务端加载入口与世界接入尚未实现，详见[仓库布局](Architecture.md)。

## 一个身体，一个权威执行方

服务端核心可用 Java、Rust 或其他支持该 Protobuf 契约的语言实现。各实现接收相同请求、返回相同语义的响应，并通过对应规则版本的行为验收；不要求共享 Rust 核心、Java 数据模型或 JNI。客户端只渲染快照并发出意图。

| 环境 | 正式状态在哪里 | 宿主承担 |
|---|---|---|
| Fabric 单机 | 内置服务端选择的权威身体实现 | 世界时间、实体/装备映射、环境真元、命中/增益、书籍学习和世界存储 |
| Fabric 专用服务器（扩展目标） | 专用服务器实例 | 复用单机服务端契约，不加载客户端 UI 类 |
| Bong | Bong 接入的权威身体实现 | 身份权限、既有世界账本、战斗/移动/库存消费者、数据库、NPC/离屏推进 |
| 其他服务端 | 实现同一协议和行为契约的身体服务 | 身份认证、世界事实、传输适配、原子提交与存储 |

连接远端服务端时，客户端不能同时运行另一份本地权威身体。Bong 及其他非 Fabric 宿主不需要安装 Fabric JAR；它们选择实现方式，按固定版本的协议与内容定义接入。

Protobuf 只定义数据交换，不自动提供服务器、网络连接或身份认证。传输适配负责把完整请求交给处理器，把响应交回调用方；处理器依赖宿主提供可信身份、权限与状态来源。进程内调用和跨进程通信均可采用这一边界，具体网络协议不由身体数据层强制指定。

## 已实现的附属内容安装

宿主收集 `AddonBundle`，通过 `AddonRegistration` 提供自己的宿主 ID、内容 API 修订与实际能力，调用 `AddonRegistry.load`。注册过程有界且整批校验，失败不产生半套目录。成功后把 `registry.recipes()` 传给现有命令处理器，并通过 `findTemplate(id, version)` 读取已验证出生构型。详细字段与可用代码片段见[附属包接入](Addon-Integration.md)。

清单、能力画像与安装入口必须来自可信宿主，不能由玩家网络请求提供；“安装”不等于“角色已学会”。`inspectReferences` 返回可用、缺包、缺定义或缺精确版本，宿主保留原引用并展示原因。已有身体快照和正在加工的固定配方按保存事实恢复，不用当前模板重套数值。

当前未提供 JAR 自动发现、Fabric 加载回调、客户端资源校验或热重载。Java 参考注册表只负责声明式内容，其他语言按相同 Protobuf 与注册/缺包语义实现；它们不执行 JAR 中的 Java 字节码。

## 已实现的 Java 查询入口

`dev.kizuna.meridian.server.BodyQueryHandler<C>` 接收一个完整请求的 `byte[]`，输出一个完整响应的 `byte[]`。装配时必须提供 `BodyReadPolicy<C>` 与 `BodySnapshotReader`，随后调用 `handle(trustedContext, payload)`：

| 宿主提供项 | 契约 |
|---|---|
| 可信调用上下文 `C` | 由宿主认证并绑定当前连接或本地调用；不从客户端请求反序列化身份 |
| `BodyReadPolicy<C>.canRead` | 决定该上下文是否有权读取目标的完整身体；可组合私有策略，无默认放行 |
| `BodySnapshotReader.find` | 返回指定身体的不可变权威快照或 `Optional.empty()`；查询不创建身体、不推进时间、不写状态 |

这些 Java 接口是参考处理器内部与宿主的装配边界，不是另一套跨语言通信协议。其他语言实现直接按 `.proto` 与[请求处理语义](Protocol-and-Security.md)提供同等入口，无需链接 Java 类。

处理器同步执行、没有身体缓存，也不自行加锁；宿主须在权限检查与读取期间提供串行调度或等价的一致读取边界。每次查询重新检查权限，关联 ID 不用作私有快照缓存键。实际认证、限流和传输分帧由宿主完成。

## 已实现的变更与事务入口

`BodyCommandHandler<C>.handle(trustedContext, payload)` 接收完整 Protobuf 请求，执行寿元支出、时间结算、运输、器官加工启动、组织做功或环境摄入。前五个构造参数为 `BodyCommandAccess<C>`、`BodySecurityPolicy<C>`、`BodyTimeSource<C>`、`BodyTransactionStore` 与 `BodyAuditSink<C>`；第六个参数 `OrganRecipeCatalog` 省略则为空目录。摄入另需第七个参数 `BodyEnvironmentSource<C>`，未提供来源时返回 `ENVIRONMENT_UNAVAILABLE`，表示该宿主没有配置环境摄入能力。具体语义见[协议与安全](Protocol-and-Security.md)、[配方接入](Technique-Addons.md)和[环境摄入](Environment-Qi.md)。查询与变更分别路由；送错入口会在宿主回调前拒绝。

`BodyCommandAccess.authorize` 既决定该操作和剂量是否允许，也返回稳定可信调用者 ID。不能只检查“是否拥有该身体”就把所有未来操作开放给客户端；宿主应仅开放实际需要的命令。认证上下文由宿主生成，客户端不能声明 caller_id。

健康操作通过 `handleHealthEffect(context, payload)` 接入，普通 `handle` 默认拒绝。宿主先确认攻击命中或治疗、药效事实，再构造请求，不能接受玩家自报剂量。完整构造器的最后一个参数是 `BodyDamageHandler`：接收 `DamageContext` bytes，返回 `DamageResolution` bytes。宿主按实际生物模板选择 Addon；减伤、分配和生物特定死亡条件由该处理器计算，框架校验及提交局部变化。回调在事务内只计算一次方案，不写账、不调用外界副作用；其他语言按相同 Protobuf 实现即可。省略最后参数时使用 `DefaultBodyDamageHandler`：可省略命中点以随机选择尚有 HP 的部位，全部部位归零时判死。已装配策略失败时不改用兜底。治疗与主动疲劳恢复不依赖伤害处理器。

健康回执固定 Addon 原方案；Store 复算与成功重试不再调用 Addon。死亡及开脉因伤中断的真元释放与身体、回执原子保存到默认释放账户，恢复时必须带回该余额。宿主确定普通时间释放和健康释放的位置归属后，可用下述 `transferReleasedToSource` 归位；位置记录与调度仍由宿主负责。字段、剂量及失能规则见[伤害、治疗与主动恢复](Health-Effects.md)。

独立伤害适配可以通过 `BodyHealthHost.exchange(byte[])` 交换 `health_host.proto` 消息，查询实际身份绑定后向同一宿主提交健康命令。`ConfirmBodyDeath` 用于已经发生的外部死亡，保留局部 HP 与伤势。只有明确 `unbound = true` 允许寻找其他宿主；拒绝和重复认领均停止，不换账本。Fabric entrypoint 与原版事件接线属于独立 Addon，核心仍不依赖 Minecraft。详细可信边界见[健康宿主交换](Health-Effects.md#跨附属健康宿主交换)。

`BodyTransactionStore.transact(bodyId, work)` 在隔离同一身体所有写入的事务内恰好调用一次 `work`，不能通过自动重跑回调解决冲突。事务提供只读快照、成功回执查找、`environmentSource(sourceId)` 与 `stage(next, receipt)`。环境读取还须隔离其他身体对同一来源的写入；只锁身体不足以防止共享预算透支。stage 是暂存，正常返回后才能把身体、来源库存、预算与回执一起发布。任何提交前故障必须一起回滚；成功返回必须意味着提交完成。权限/会话撤销及环境位置历史由宿主与提交边界协调，数据库类型不进入框架接口。

`InMemoryBodyStore` 是可运行的参考适配，也实现 `BodySnapshotReader`。无环境历史时用快照与回执初始化；已有时间、开脉或健康命令的环境释放时，通过三参数构造器恢复 `QiInventory` 默认释放账户。区域摄入使用四参数构造器，同时传入完整 `List<EnvironmentQiSource>`；有摄入回执却缺少对应来源时拒绝恢复，不重放回执扣账。`environmentSource(id)` 读取已提交来源，`renewEnvironmentWindow` 用预期版本续窗，保留库存。`environmentQi()` 表示时间、开脉和健康命令共用的默认释放账户；摄入区间加工损耗直接回选定区域，不重复进入该账户。它用一个监视器隔离所有身体与来源，拒绝嵌套写事务、跨线程及回调结束后使用事务。它不写磁盘，没有 TTL；正式宿主需实现原子持久化、恢复及实际并发策略。

事务外的区域入账由两个可信宿主方法提供，均返回提交后的完整 `EnvironmentQiSource`：

- `creditEnvironmentSource(sourceId, credited)`：将其他宿主已经释放的真元记入指定区域，不改默认释放账户。
- `transferReleasedToSource(sourceId, amount)`：从默认释放账户扣除指定明细并记入区域；按 `QiAccounting.debit` 逐属性、逐相态核对余额且要求密度匹配，不能用其他种类抵扣。

两个方法与身体事务、登记和续窗共用监视器；事务回调内调用抛 `NESTED_TRANSACTION`，来源不存在时拒绝为 `ENVIRONMENT_UNAVAILABLE`，来源版本已达 `Long.MAX_VALUE` 时拒绝为 `REVISION_EXHAUSTED`。明细必须非空，每项由 `QiInventory` 保证正数且有限，允许混相与自定义属性。先计算全部候选并通过 `EnvironmentProtobuf.validateSource`，再一次发布；余额不足、数值极限或协议校验失败均保留全部旧账。成功仅增加区域库存和来源版本，不改变供给窗口、固定速率或已用预算；归位同时扣减默认释放账户。

这两个方法只保证单次原子入账，不保存释放 ID，也不自动识别玩家或位置。外部释放的真实性、按释放 ID 去重及归位记录由宿主负责；持久化宿主应将去重事实与入账结果一起保存，避免崩溃重试重复发放。默认账户中的余额归位须使用转出方法，不能用外部入账方法复制一份。来源版本变化后，旧 offer 的交换返回 `ENVIRONMENT_REVISION_CONFLICT`；宜先结清旧窗摄入，再在同一宿主串行边界集中入账/归位，用最后返回的 revision 调用 `renewEnvironmentWindow`，随后重新生成 offer。各方法单独加锁不代表多次调用自动组成一个事务。

[环境交换契约](Environment-Qi.md)通过 `environment.proto` 表达来源库存、供给、交换及成功回执。`EnvironmentAbsorption.initialize/renewWindow` 在窗口开始时利用 `EnvironmentQiRules` 按浓度计算速率；交换期间从真实库存扣账，按重叠子区间检查多人共享供给。`EnvironmentConfigLoader` 从 TOML 提供配置。`BodyEnvironmentSource.resolve(context, body, plan)` 必须确认 `[body.settledAt, plan.target)` 全程可从返回的来源摄入，而不是只看最后位置。移动、环境条件或离线模式改变时由宿主先拆段；这个只读接口不预扣款，也不接收客户端提供的 offer。

摄入命令自身推进身体时间，不要求像发运命令那样先结算到 target。它在成功时同时保存可信计划和环境回执，且只增加一次身体 revision。处理器已完成重试时直接返回旧提交；不会再询问当前时间、位置或来源，因此过期窗口也不会使已成功的重试再次扣账。Java 回调用于进程内装配，跨语言服务使用同一 `.proto` 和上述事务行为；无需加载 Java 实现。

环境空间与缓存使用 `EnvironmentTopologyProtobuf`：宿主按生成结果或内容定义声明 `EnvironmentSpace`，普通背景、房间和通道各持有唯一来源身份。已确认采用随机区域及边界传播倍率，不接入世界扫描、方块变化监听和修炼室自动识别。`EnvironmentBoundaryCache` 汇总共享边界的分片贡献，显式参数变更通过 Protobuf 更新。宿主保存生成结果、位置归属及当前账本；定义失效时先停用相关缓存，确认后原子发布新空间与连接，详见[空间与边界接入约定](Environment-Configuration.md)。

`TerrainQiGenerator` 已接受可信 `TerrainQiRegionRequest` / `TerrainQiBoundaryRequest`，返回按地形区间与五行偏好生成的初始候选及共享边界。宿主从地形生成元数据提供群系，不查询方块；按来源唯一键原子初始化一次，并保存实际库存。`TerrainQiProtobuf` 校验输入与候选，但不执行初始化事务。主世界配置、未知群系和确定性算法见[地形灵气生成](Terrain-Qi-Generation.md)。

这些 Java 回调仅用于进程内装配，不是第二套跨语言协议。持久化回执由 protoc 生成的 `CommandReceipt` 表达；其他语言自行实现相同事务语义，不要求加载 Java 类。时间回执中的释放账户必须纳入环境事务，实体死亡动作的可靠记录也由宿主一起保存；审计回调只是提交后的尽力通知。

`InMemoryBodyStore.receipts()` 返回全部已提交成功回执的不可变列表。宿主可在自身串行事件边界，将它与身体快照和环境余额一起持久化；读取这三个入口并不构成跨调用的自动事务，并发宿主须另行隔离。审计回调仍不能替代已提交回执作为持久化事实来源。

可信宿主可用 `registerBody` 登记首次出生、用 `registerEnvironmentSource` 登记首次生成区域，二者拒绝重复身份和事务内嵌套写入。所有身体共用同一 Store 才能共享区域库存与供给预算。保存时一起读取身体、回执、`environmentSources()` 与默认释放账户；恢复直接使用这些事实，不重抽环境或重新发放历史损耗。

## 可信时间与离线策略接入

`BodyTimeSource.resolve` 只在新时间、运输、加工启动或做功命令通过授权、去重和版本检查后调用，返回 `TimeAdvancePlan`。宿主确定时钟域/世代、目标模拟毫秒、老化倍率及模式。后三类资源操作要求同一已结算模拟时刻且为 `ACTIVE`，否则先结算、读取新 revision 再操作；这组操作使用同一逻辑时刻，而不是每次读取变化的墙钟。源回调必须只读。

- 单机暂停可停止发送结算命令；相同游标会返回 `TIME_NOT_ADVANCED`。世界运行时的老化倍率由宿主配置。
- Bong 世界内退出可以沿用 `ACTIVE`；已有经脉/器官到达与加工完成，组织端口到达仍拒绝；完整 NPC 决策与自动配方安排仍待接入。
- Bong 藏身处退出使用 `LIFESPAN_ONLY`；宿主负责将可信离线时长纳入模拟游标，其余状态冻结，运输剩余时间保持不变。
- 倍率或模式变化前先按旧策略结算至切换点；跨世代游标迁移尚未实现，现阶段明确返回 `CLOCK_MISMATCH`。
- 用 `BodyTimeAdvancement.nextEventBoundary` 安排最近的寿尽/运输到达/加工完成；发运、启动加工、寿元支出或策略变化后重算。阻塞货物与任务在通路、容量或器官恢复后显式结算，不周期重试。框架不内置后台定时器。

时间计划可通过 `TimeProtobuf.encodePlan/decodePlan` 在已认证的宿主内部边界交换；不要将其注册成客户端网络入口。年龄计算、毫秒取整、死亡优先级与数值限制见[身体与持久化](Body-and-Persistence.md)。

## 查询验证与回归边界

`./gradlew build` 保留身体数据、真元账本、Protobuf 固定字节、授权、并发/去重、时序与寿尽、共享吞吐、容量预留、阻塞恢复、运输/加工冻结、组织负载、配方守恒、缺包恢复、死亡全账户释放与原子回滚等关键回归，并核验分发产物包含当前协议。回执恢复使用内存与 Protobuf 往返，不代表已有磁盘保存或游戏重开验收。

2026-10-08 已通过 Python → Java → Python 一次性查询验证：两端从同一 `.proto` 独立生成消息，使用固定身份与合成快照，以标准输入输出交换请求和响应。验证覆盖完整快照、六境、混相、int64 时间与版本精度、两种业务拒绝及四类非法输入。临时 Python 脚本、Java 测试宿主及对应 Gradle 任务已移除，不再维护额外测试工具链。

该记录只证明当时版本的查询通信互通，不代表持续执行跨语言回归，也不代表已有 Python 身体执行器、网络服务器、持久化或 Fabric/Bong 实装。接入实际其他语言宿主时，应针对其实现重新验证协议与行为契约。

## 数据输入与世界输出

宿主提供可信时间、身体身份、实际装备/学习事实、环境供给和动作上下文。核心校验并计算身体变化、资源需求和能力结果，宿主将它们与世界副作用一起提交。

材料不足、世界账户不足、身体 revision 过期或安全策略拒绝时，不留下半次扣款。具体数据库事务、存储记录或提交协调由宿主实现，但要通过共同的原子性和重试契约验收。

核心保存寿命和身体状态，宿主提供保存后端及真实时钟，不各算一份年龄。Fabric 必须提供可直接使用的单机存储适配；Bong 可以对接既有数据库，而非把数据库类型暴露给公共 API。

## 接入方式与发行

首片已提供 Protobuf 请求 → 宿主读取权限校验 → 权威快照查询 → Protobuf 响应的参考处理器。宿主将实际认证上下文与状态来源接入后，复用此入口；其他实现遵守相同契约，不以原生库装载作为接入前提。

具体部署若选择 JNI 或其他原生桥接，应单独验证 ABI、错误隔离、资源释放和声明支持的平台，并交付所需运行依赖。纯 Java 实现不因此增加原生平台要求。单机必须可独立安装、运行与保存，不要求用户部署额外远端服务。

客户端通过公共 Inventory UI 能力接入，服务端入口不加载 owo 或客户端专用类。依赖声明和打包方式需要实测；目前没有 Gradle 依赖坐标或安装命令。

## 最小接入验收

同一模板、配方、初始状态和宿主事实分别在两种环境完成：获得配方 → 调元 → 对应肌肉增益 → 攻击 → 真元回环境 → 保存 → 重开。最终身体结果一致，世界效果按各宿主的实际消费者结算。

基础模组不认识具体 Bong 功法 ID。不同实现须遵守相同规则版本的结果与守恒约束；同一身体不由客户端和服务端重复结算。缺少某个扩展算子时按能力协商失败，而不是私下替代。
