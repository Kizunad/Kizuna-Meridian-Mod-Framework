# 请求协议与安全扩展

状态：**v1 Protobuf 数据契约、Java 查询与变更处理器、寿元支出/推进、经脉与器官运输、组织能力/做功、器官配方、环境摄入及原子交换的内存事务参考已实现，尚未发布。** 授权、可信时间、事务与私有安全策略由宿主注入；网络注册、连接认证、磁盘持久化、编辑会话和实际宿主调度尚未实现。

## 唯一协议来源与构建

附属进度契约新增 `BodySnapshot.addon_progress = 16` 和 `BodyCommand.set_addon_progress = 28`。后者只允许可信 `BodyCommandHandler.handleAddonProgress` 入口；普通请求、健康与捕获入口均拒绝。仍检查身份、预期 revision、幂等回执和提交快照，不能夹带真元、生命或结构变化。

内部视图使用 `BodyViewRequest` 请求/撤销订阅；`BodyViewUpdate` 的 `request_id`、`failure`、`introspection` 表达回执和当前授权视图。宿主决定权限，基础体表检视与修炼订阅分开。界面隐藏不替代服务端对吐纳、内观和开脉的授权。

接触摄入 `BodyCommand.capture_qi = 22` 只允许可信宿主在确认粒子接触后调用 `handleCapturedQi`。普通 `handle` 即使收到合法 Protobuf 且宿主普通授权放行，也返回 `ACCESS_DENIED`；不得把可信入口直接注册到玩家网络通道。命令引用器官、配方版本和宿主配置的采样体积，实际剂量由锁定环境浓度计算；`CommandReceipt.qi_capture = 10` 保存配方及瞬时环境交换。相同请求重放不会再次扣账或发放产物。

按照 Kizuna 各框架的惯例，跨模组、跨语言和宿主边界使用 `.proto` 定义消息，通过 `protoc` 生成类型。这里不再使用 TypeBox → JSON Schema 方案，也不手工维护一套 JSON 协议。

| 源码 | 内容 |
|---|---|
| `shared/src/main/proto/kizuna/meridian/v1/body.proto` | 模板、实际构型、独立部位/组织/器官/经脉、端口与连接、六境、真元明细、寿命、时间游标和完整快照 |
| `shared/src/main/proto/kizuna/meridian/v1/protocol.proto` | 查询、寿元支出/时间结算/运输意图、内部可信时间计划、成功结果、持久化回执和公开拒绝类别 |
| `shared/src/main/proto/kizuna/meridian/v1/health.proto` | 伤害上下文与 Addon 裁定、独立 HP 治疗、主动疲劳恢复及健康回执 |
| `shared/src/main/proto/kizuna/meridian/v1/health_host.proto` | 可信身份到权威身体的查询及健康效果交换，明确区分未绑定与拒绝 |
| `shared/src/main/proto/kizuna/meridian/v1/environment.proto` | 可信环境供给、候选吸收/释放交换和双账户提交回执 |
| `shared/src/main/proto/kizuna/meridian/v1/addon.proto` | 可信附属清单、宿主能力、依赖、模板/配方注册、内容引用及缺包诊断 |
| `shared/src/main/proto/kizuna/meridian/v1/gathering.proto` / `breathing.proto` | 聚气查询、单轮启动、手动吸呼、可信生物呼吸能力及只读投影；详见[聚气编排](Gathering.md) |
| `dev.kizuna.meridian.protocol.pb.BodyMessages` / `MeridianMessages` / `EnvironmentMessages` / `AddonMessages` | protoc 生成的 Java 类型，不手改、不提交生成目录 |
| `BodyProtobuf` | 核心模板/快照与生成类型双向转换、独立二进制编解码 |
| `MeridianProtocol` | 请求/响应二进制编解码、版本与方向校验 |
| `HealthMessages` / `HealthProtobuf` / `server.BodyDamageHandler` | protoc 生成健康消息、有界编解码与纯伤害计算装配接口 |
| `HealthHostMessages` / `HealthHostProtobuf` / `server.BodyHealthHost` | 可信跨附属查询与健康意图交换，复用核心命令与失败码 |
| `TimeProtobuf` / `server.BodyTimeSource<C>` | 可信时间计划的编解码、结果校验；宿主提供目标、倍率与模式 |
| `EnvironmentProtobuf` | 环境供给、交换及回执的编解码；版本、区间、逐类额度和合计速率校验 |
| `IntakeProtobuf` / `core.OrganIntake` / `environment.EnvironmentAbsorption` | 通用器官摄入、环境真实扣账与共享区间预算；可信来源由 `BodyEnvironmentSource` 提供 |
| `TransportProtobuf` / `core.QiTransport` | 运输请求映射、内腔扣源/在途创建及到达规则 |
| `CapabilityProtobuf` / `OrganRecipeCatalog` | 组织能力与做功映射、器官配方编解码及可信目录注册 |
| `AddonProtobuf` / `server.AddonRegistry` | 包级内容编解码、依赖/能力/引用校验、不可变目录与缺包报告；可信安装入口 |
| `server.BodyQueryHandler<C>` | 解码查询、调用宿主读取策略与快照来源、校验目标身份并返回响应 |
| `server.BodyReadPolicy<C>` / `BodySnapshotReader` | Java 宿主提供的读取授权与权威快照入口，不属于另一套线协议 |
| `server.BodyCommandHandler<C>` | 变更授权、回执去重、版本检查、核心计算、私有策略、提交前重验与原子暂存 |
| `server.BodyTransactionStore` / `InMemoryBodyStore` | 宿主事务接口与内存参考实现；快照、成功回执与死亡环境释放一起提交 |
| `server.BodyCommandAccess<C>` / `BodySecurityPolicy<C>` / `BodyAuditSink<C>` | 意图授权与可信调用者、私有检查、提交后只读审计 |

生成消息由两端分别构建；表中的手写编解码器、校验和处理器位于 `server/src/main/java/`，客户端通过生成消息表达请求与读取响应。

Protobuf 插件固定 `0.9.6`，`protoc` 与 `protobuf-java` 固定 `4.36.2`，与 Inventory UI / Botany 当前工具链对齐。Java 目标为 17。Gradle 自动下载编译器，无需安装系统级 protoc：

```bash
./gradlew generateProto
./gradlew build
python3 "scripts/wiki.py" check
```

根目录命令聚合两端任务；单独构建可运行 `./gradlew :client:build` 或 `./gradlew :server:build`，服务端整体回归可运行 `./gradlew :server:test`。

| 构建产物 | 内容 |
|---|---|
| `client/build/libs/kizuna-meridian-mod-framework-client-0.1.0-SNAPSHOT.jar` | 客户端生成消息与协议资源 |
| `server/build/libs/kizuna-meridian-mod-framework-server-0.1.0-SNAPSHOT.jar` | 服务端身体计算、处理器、生成消息与协议资源 |
| `build/distributions/kizuna-meridian-mod-framework-0.1.0-SNAPSHOT-proto.zip` | 跨语言契约包 |

两端 JAR 均包含原路径 `.proto` 和 `META-INF/kizuna_meridian/protocol/meridian-v1.desc`；ZIP 包含 `.proto` 与 `descriptors/meridian-v1.desc`。描述集包含导入与源码信息。根任务 `verifyProtocolArtifacts` 检查三份产物内的协议与当前源码一致，并验证客户端未带入服务端身体实现，随根工程 `check` 执行。

当前两端产物仍是 Java 库，尚非可安装 Fabric 模组，也没有发布 Maven 坐标。`protobuf-java` 是公开依赖，不在这两个 JAR 中内嵌；未来 Fabric 打包需要另外验证依赖装载。任何支持本契约的语言均可生成消息并实现服务端，共用字段号、二进制规则与行为契约；不限定 Rust，不要求 JNI。Protobuf 不自动提供传输、认证、请求处理或身体计算，这些仍需对应实现。

## 已实现的请求与事实

| 消息 | 方向/用途 | 已实现校验 |
|---|---|---|
| `RequestEnvelope.get_body_snapshot` | 调用方 → 权威宿主，查询指定 `body_id` | 协议版本、关联 ID、明确意图、身体标识 |
| `RequestEnvelope.body_command` | 调用方 → 权威宿主，寿元支出、时间结算、运输、器官加工或组织做功 | 身体标识、显式预期 revision；按操作验证参数；时间请求无参数 |
| `get_tissue_capability` / `tissue_capability` | 查询/响应 | 实际身体和组织 ID；同一读取授权，返回当前能力及 revision |
| 独立 `OrganRecipe` 字节 | 可信内容安装，不是客户端可上传的请求 | ID/版本、器官类型、最低境界、输入/输出守恒及正数时长 |
| 独立 `AddonRegistration` / `AddonBundle` 字节 | 可信宿主安装附属内容，不在 `RequestEnvelope` 中开放 | 包格式、宿主画像、依赖版本/循环、命名空间归属、定义冲突与类型引用 |
| `ContentReferences` / `ContentAvailabilityReport` | 宿主保存引用及查询当前目录可用性 | 精确 ID/种类/版本、状态与列表边界；不授予执行权限，不自动迁移 |
| 独立 `TimeAdvancePlan` 字节 | 可信宿主内部事实，不是客户端请求分支 | 目标游标、显式有限非负倍率、推进模式 |
| `BodyCommand.intake_qi` / `CommandReceipt.organ_intake` | 摄入意图 / 可信计划与环境提交事实 | 器官 ID、正数原始明细；成功推进年龄与游标，并与环境原子交换 |
| `BodyCommand.start_meridian_opening = 19` / `pause_meridian_opening = 20` | 开脉启停意图 | 从目录解析固定规则/动作；当前游标、权限、版本、资源及重复请求校验 |
| `CommandReceipt.opening = 9` / `BodySnapshot.opening_task = 15` | 启停事务事实 / 保存的任务 | 完整规则、动作、参与组、时间、状态、真实摄入证据及释放；事务复算核对 |
| 独立 `GatheringRequest` / `GatheringResponse` | 授权会话上的单轮聚气操作与展示 | 有界解码、请求版本、目标身份、预期 revision、真实配方与线路；每个资源步骤复用原有命令处理器 |
| 独立 `EnvironmentQiOffer` / `EnvironmentQiExchange` / `EnvironmentQiReceipt` / `EnvironmentQiSource` 字节 | 可信宿主与权威身体实现之间的环境事实及来源存档 | 来源/身体身份、版本、有效期、毛额/速率、共享预算及提交版本；详见[环境契约](Environment-Qi.md) |
| `ResponseEnvelope.body_snapshot` | 权威宿主 → 有权读取完整身体的调用方 | 完整身体与所有模块、账户、版本、时序的核心校验 |
| `ResponseEnvelope.command_committed` | 权威宿主 → 已授权调用者 | 身体身份、前后 revision，恰好增加 1 |
| `ResponseEnvelope.failure` | 权威宿主 → 调用方 | 固定公开错误类别，不包含堆栈或私有策略详情 |
| 独立 `BodyTemplate` / `BodySnapshot` 字节 | 内容交换/未来存档后端 | 格式、规模与核心数据一致性；不授予状态覆盖权限 |
| 独立 `CommandReceipt` 字节 | 宿主内部保存成功回执 | 可信调用者、请求 ID、已解码命令及提交结果一致性；推进命令必须附带 `time_advance` 结算记录 |

`MeridianProtocol.encodeRequest/decodeRequest` 和 `encodeResponse/decodeResponse` 交换整个消息的原始 Protobuf 字节，不外包 JSON、字符串长度或 `writeDelimitedTo` 前缀。每次调用接收一个已经由宿主分帧的完整消息；网络通道和分片策略尚未注册。**1 MiB 是此编解码器的上限，不表示 Minecraft 的任何单包通道可以直接承载 1 MiB；宿主必须遵守实际传输层的更小限制。**

查询 `request_id` 只关联结果；变更请求另外把它纳入去重键，两者都不证明身份、权限或目标归属。完整快照包含内部结构与寿命，不可当作公开广播投影。请求没有上传快照或直接设置 HP、余额、寿命的入口；寿元支出是必须独立授权并执行核心规则的意图。

两端均可使用以下生成消息 API；服务端接收到字节后，通过 `MeridianProtocol` 执行业务格式校验，再进入处理器授权：

```java
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

var request = MeridianMessages.RequestEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setRequestId("q1")
        .setGetBodySnapshot(MeridianMessages.GetBodySnapshot.newBuilder()
                .setBodyId("body-1"))
        .build();
byte[] payload = request.toByteArray();
var decoded = MeridianMessages.RequestEnvelope.parseFrom(payload);
// parseFrom 只完成 Protobuf 解析；请求内容和读取权限由服务端继续校验。
```

固定查询字节为 `08011202713152080a06626f64792d31`；相同关联 ID 的 `ACCESS_DENIED` 响应为 `0801120271315a020802`。契约测试固定这两组字节，避免编码与解码同时改错而往返测试仍通过。可独立使用 protoc 校验查询样例：

```bash
protoc --proto_path="shared/src/main/proto" \
  --encode=kizuna.meridian.v1.RequestEnvelope \
  "kizuna/meridian/v1/protocol.proto" <<'EOF'
protocol_version: 1
request_id: "q1"
get_body_snapshot { body_id: "body-1" }
EOF
```

## 数值、存在性与有界解码

- 协议版本、快照 schema、模板内容版本分别记录，当前线协议只接受 `1`，身体快照 schema 只接受 `3`。
- 时间与 revision 使用非负 `int64`，精确保留至 `Long.MAX_VALUE`；真元与其他连续量使用有限 `double`，沿用[基础数据定义](Data-Definitions.md)的单位与机器舍入规则。
- 合法为零的数值与布尔值声明为 `optional`，**v1 业务语义仍要求显式提供**。`0`、`false` 与缺失不同；单值嵌套消息通常必填，空账户须显式提供空 `QiInventory`。条件字段为：`CommandReceipt.time_advance` 仅在时间命令必填，`organ_recipe` 仅在启动加工命令必填，`organ_intake` 仅在摄入命令必填，其他命令须缺省；`OrganState.process` 在空闲时缺省，`OrganDefinition.intake` 缺省表示无摄入能力。
- 手动吐纳的 `BreathControl.aim_z` 是可省略的方向深度；省略时执行方取入口平面 `0.5`。显式提供时必须有限且位于 `[0,1]`，不改变其他字段的必填规则。
- 字符串非空白；所有单字符串最多 1024 个 UTF-8 字节，单列表最多 4096 项，全消息已知字段值最多 65536 个，整个载荷最多 1,048,576 字节，解析深度最多 32。
- 未设置或未知枚举、未选择 oneof、未知字段、超限、截断、非法 UTF-8 和非有限数字全部拒绝。当前精确版本不会默默丢弃未知身体字段；不是任意新版消息都能由旧版读取。
- `parseFrom` 只证明 Protobuf 格式可解码。`BodyProtobuf` 还执行核心构造校验，检查重复 ID、端口引用、状态对应、容量与 HP 上限、重复真元明细和在途时序。禁止把生成 Builder 当成已校验权威对象。
- 不传独立可写的总真元、剩余寿命或总体 HP 比例，接收方从同一明细派生。解码不推进时间、不扣元、不触发生死或执行功法。

字段号与枚举数值固定在 `.proto`，不使用 Java `ordinal()`。移除字段或枚举值时保留 `reserved`；修改必填规则或语义必须调整相应版本，并增加固定样例与拒绝测试。当前 Protobuf 原生单值/oneof 重复字段按其标准合并规则解析，不承诺二进制载荷具有唯一表示；宿主不能用原始字节哈希代替请求 ID 与业务幂等控制。

身体 schema 3 要求 `MeridianState.opening = 6`，其消息为 `MeridianOpening { stage = 1; progress = 2; }`；`Qi.phase = 1` 已保留，真元密度由 `Qi.density = 4` 保存并推导相态。阶段编号是 `UNOPENED = 1`、`IN_PROGRESS = 2`、`OPENED = 3`；进度声明为 `optional double`，合法的零也须显式提供。缺失或未知阶段、非有限进度及阶段与进度不符均拒绝，具体规则见[开拓数据](Data-Definitions.md)。固定字节与快照整体回归保护字段编号、零值存在性及逐脉状态；协议没有直接设置开拓进度的客户端请求。

摄入新增 `OrganDefinition.intake = 9`、`BodyCommand.intake_qi = 15`、`CommandReceipt.organ_intake = 7`，失败码 `29–33` 分别为 `INTAKE_UNSUPPORTED`、`ENVIRONMENT_UNAVAILABLE`、`ENVIRONMENT_REVISION_CONFLICT`、`ENVIRONMENT_INTERVAL_INVALID`、`ENVIRONMENT_BUDGET_EXCEEDED`。身体 schema 为 3，线协议仍为开发中的 v1；旧消息未声明摄入能力时含义明确，携带新字段的消息需要同步契约包，旧严格解码器会拒绝。既有死亡、模块失能、容量、吞吐、时钟和数值边界复用原失败码。

开脉扩展新增可缺省 `OrganDefinition.endurance = 10`、`OrganState.fatigue_joules = 5` 和 `BodySnapshot.opening_task = 15`；未声明器官耐力时疲劳须为零。`CommandReceipt.opening` 仅开脉启停命令携带，普通时间推进产生的完成或中止释放仍归 `time_advance`。开脉规则已移至 `body.proto` 供任务内嵌，生成 Java 类型属于 `BodyMessages`，Protobuf 全名与原字段号保留。失败码 `40–44` 为 `OPENING_RULE_NOT_FOUND`、`OPENING_BUSY`、`MERIDIAN_ALREADY_OPENED`、`OPENING_REQUIREMENTS_UNMET`、`OPENING_NOT_ACTIVE`。客户端无上传摄入证据、临时额度或完成标记的入口；字段细节见[开脉契约](Meridian-Opening.md)。

`MeridianOpeningRule` 保留 `hold_qi_per_second = 20` 与 `policy_id = 22 / policy_config = 23`。旧条件字段 `5、7、8、11、12、14、19、21` 及名称 reserved，其中 `21` 原为 `guidance_buffer_qi`；人体条件改由 Human 自己的 Protobuf 提供。框架只保存 Any 并通过 `opening_policy.proto` 请求可信策略裁定。`OpeningPolicyDecision` 返回引导目标体积、条件结果与下一检查边界，核心再校验引用和资源边界。失败码 `OPENING_POLICY_UNAVAILABLE = 45` 表示执行器缺失；已保存活动任务遇到缺策略会安全暂停并解除动作占用，保留合法库存和稳定体积。客户端不能上传策略或裁定，仍只选择可信规则；权威时间推进回执保存凝聚耗散的环境释放。

健康扩展为 `BodyCommand.damage_body = 23 / heal_body = 24 / restore_fatigue = 25`、`CommandReceipt.health = 11`，失败码 `DAMAGE_HANDLER_UNAVAILABLE = 46`。三种命令仅可信 `handleHealthEffect` 接受，普通 `handle` 默认拒绝。`DamageBody.kind = 5` 为必填非零致伤分类，`hit = 3` 可省略并由策略选点；原字段号不变，开发中 v1 的旧伤害请求须同步分类字段。默认处理器等概率命中存活部位，只在全部部位归零时判死；Addon 可完整替换该策略。固定 `DamageResolution` 保存实际目标与判死结果，框架验证及提交，重试不重新随机。已装配策略拒绝时不改用默认策略。治疗与恢复也不得由客户端自报剂量。完整边界与重试语义见[伤害、治疗与主动恢复](Health-Effects.md)。

`BodyCommand.confirm_body_death = 26` 同样只接受可信健康入口：宿主确认外部死亡后标记死态、释放真元并保留局部伤势，不经过伤害策略。`health_host.proto` 的查询与效果交换要求协议版本、关联 ID、唯一宿主与实际身体绑定；明确失败不能视作未绑定。它不定义玩家自报伤害网络包，也不提供复活命令。

## 已实现的只读请求处理

`BodyQueryHandler<C>.handle` 的顺序为：完整请求校验 → 可信上下文检查 → 读取权限策略 → 读取权威快照 → 检查快照身份与目标一致 → 完整响应校验与编码。它没有默认权限策略，也不持有或修改身体状态。

| 条件 | 结果 |
|---|---|
| 合法请求、允许读取且身体存在 | 保留请求 ID，返回所请求身体的完整快照 |
| 可信上下文为 null，或读取策略拒绝 | `ACCESS_DENIED`；不调用快照来源，不暴露身体是否存在 |
| 允许读取，但来源返回 `Optional.empty()` | `BODY_NOT_FOUND`；不创建默认身体 |
| 策略或来源抛出运行时异常、来源返回 null、返回另一具身体、快照不满足线协议上限 | `INTERNAL_ERROR`；不返回部分身体，内部详情只进服务端日志 |
| 非法、超限、截断、未知字段、缺少意图或不支持版本的请求 | Java 入口抛出 `IllegalArgumentException`，不调用宿主策略或来源；由传输适配拒绝或丢弃 |

最后一类请求没有通过完整信封校验，处理器不伪造关联 ID，也不自动发送 `INVALID_REQUEST` 或 `UNSUPPORTED_VERSION` 响应。枚举中保留这些类别，但当前查询入口不在解析失败后尝试从不可信字节提取关联信息。

每次查询重新检查权限；查询不按请求 ID 缓存旧快照、不增加 revision、不推进年龄或真元。查询与变更入口误路由时抛出 `IllegalArgumentException`，不调用宿主。其他语言接入方需复现同等处理语义，既有跨语言查询验证不代表已验证本次变更，见[宿主接入](Host-Integration.md)。

## Java 错误与异常定义

运行时代码的错误文案、异常创建和业务拒绝类型统一在 `server/src/main/java/dev/kizuna/meridian/error/MeridianErrors.java`。同一文件内按 `Data`、`Protocol`、`Host` 分组，分别存放数据、协议与宿主诊断；字段名称、触发条件、处理分支及日志输出位置仍留在原来的业务流程中，便于按执行顺序阅读。

- 无效输入继续使用 `IllegalArgumentException`；宿主或事务状态不一致使用 `IllegalStateException`；显式必填引用为空继续使用 `NullPointerException`。解码异常保留原始原因链。
- 核心寿元业务拒绝使用 `MeridianErrors.LifespanRejected` 和 `MeridianErrors.LifespanRejectionReason`，由处理器映射为公开失败类别。寿元算子不再内嵌异常定义。
- 时间推进业务拒绝使用同一文件中的 `TimeRejected` 和 `TimeRejectionReason`；时钟与数值边界由算子判断，不把触发条件搬进错误定义文件。
- 运输业务拒绝使用同一文件中的 `TransportRejected` 和 `TransportRejectionReason`，源不足、容量、吞吐和通路原因均映射固定失败码。
- 组织/器官特有拒绝使用 `CapabilityRejected` 和 `CapabilityRejectionReason`；共同的生命、版本、资源与数值失败复用现有类别。
- 线协议失败码仍由 `protocol.proto` 的 `FailureCode` 唯一定义。Java 文案、堆栈及异常类名不进入响应，也不作为跨语言契约。

## 已实现的变更与原子提交

`body_command.spend_lifespan.seconds` 通过 `core.LifespanSpending` 只增加已耗寿元与身体 revision，不推进年龄、时间、运输或改变真元。剂量单位为生物秒，须严格小于剩余寿命；死体拒绝。若浮点舍入使支出不可见或最后余额归零，返回 `NUMERIC_LIMIT`。自然寿尽由下述时间结算统一处理，致死寿元支出仍未开放。

处理顺序：有界解码 → 宿主意图授权并取得稳定调用者 ID → 身体串行事务 → 重验授权 → 检查成功回执 → 读取权威快照/检查身份及 revision → 时间/运输意图读取可信计划 → 核心纯计算 → 私有安全策略 → 再次授权且调用者不变 → 验证新状态、回执与响应可编码 → 暂存 → 原子发布 → 只读审计。进入事务时重验可防止排队期间撤权后探测身体存在性；身体在事务内保持稳定，宿主还须协调权限/会话撤销与最终提交的一致性。

| 条件 | 结果 |
|---|---|
| 合法命令且全部检查通过 | 新身体与成功回执一起提交，死亡时同时入环境账；revision 增加 1 |
| 未认证或未授权 | `ACCESS_DENIED`，在读取身体及回执之前拒绝 |
| 同一可信调用者、身体和请求 ID，命令相同 | 重验授权后返回原成功结果；不再读时钟、执行私有策略、扣款、释放或通知审计 |
| 同一成功键但剂量/预期版本/操作不同 | `REQUEST_ID_CONFLICT`，不覆盖回执 |
| 身体不存在 / 预期版本不同 | `BODY_NOT_FOUND` / `REVISION_CONFLICT`，无写入 |
| 身体已死 / 剩余寿命不足 | `BODY_DEAD` / `INSUFFICIENT_LIFESPAN`，无写入 |
| revision 已达 `Long.MAX_VALUE` / 无法表示有效支出 | `REVISION_EXHAUSTED` / `NUMERIC_LIMIT`，无写入 |
| 私有策略返回 false / 提交前授权失效或身份变更 | `ACCESS_DENIED`，无写入 |
| 策略异常、宿主返回串号状态、事务故障 | `INTERNAL_ERROR`；细节只进日志，确认丢失时提交结果可能不确定 |
| 审计异常 | 已提交结果仍成功，记日志；不回滚、不重跑 |

成功去重键固定为 **可信 caller_id + body_id + request_id**，身份由 `BodyCommandAccess.authorize` 绑定认证上下文并跨重连保持稳定。相同命令按解码后的 Protobuf 值比较，不能按原始字节哈希；参数或预期 revision 改变应使用新 ID。业务拒绝不写回执、不保留请求 ID；在条件改变后重试会重新检查。

当前成功回执没有 TTL，在同一身体生命周期内保留；宿主不能重用身体身份、重置 revision 或单独丢弃回执。若以后需要清理，必须先定义可拒绝过期重试的协议，再缩短保存范围。内存参考实现不自行清理，也不适合充当长期持久化后端。

超时、断连或 `INTERNAL_ERROR` **不证明操作未提交**。须先用相同可信身份、请求 ID 和原始命令重试，已有回执则返回原结果。重新生成 ID 并刷新 revision 可能造成第二次合法支出。回执使用 `MeridianProtocol.encodeReceipt/decodeReceipt` 保存/恢复，与身体共用提交和备份边界。

固定寿元支出样例（`q1`、`body-1`、revision 0、5 生物秒）为 `0801120271315a150a06626f64792d3110005209090000000000001440`，成功响应为 `080112027131620c0a06626f64792d3110001801`。新增字段扩展尚未发布的 v1 开发契约，不表示旧构建接受新消息；各实现需同步同一契约包。

## 已实现的可信时间结算

`body_command.advance_time {}` 只表示请求结算，固定样例（`q1`、`body-1`、revision 0）为 `0801120271315a0c0a06626f64792d3110005a00`。客户端往该空消息添加时间字段会被未知字段校验拒绝。宿主须单独授权操作，不能把身体读取权限当作时间控制权限。

`BodyTimeSource.resolve(context, current)` 在身体事务内返回 protoc 生成的 `TimeAdvancePlan`。字段为 `target`、`biological_seconds_per_simulation_second` 和 `mode`；跨进程可信事实可由 `TimeProtobuf.encodePlan/decodePlan` 交换。这条通道必须由宿主认证，不能注册成客户端上传入口。倍率零值也必须显式提供。恢复半衰期来自实际构型的 `TissueDefinition.recovery_half_life_seconds`，占用来自 `BodySnapshot.actions`；旧休息名单字段已 reserved，规则见[组织恢复](Tissue-Recovery.md)。

`BodyTimeAdvancement` 执行[年龄、事件顺序、冻结与寿尽规则](Body-and-Persistence.md)。新增失败码固定为 `CLOCK_MISMATCH = 12`、`TIME_NOT_ADVANCED = 13`、`PENDING_TIME_EVENT = 14`。时间平移溢出或无法表达的年龄增量复用 `NUMERIC_LIMIT`，死亡后新请求为 `BODY_DEAD`。

时间命令的成功回执必须包含 `time_advance`：所采用的计划、实际结算游标、显式死亡标记和释放账户；其他命令不得携带此字段。非死亡结果必须结算到目标，`ACTIVE` 可携带加工损耗，`LIFESPAN_ONLY` 非死亡释放须为空；死亡结果可停在更早寿尽边界。事务还需用原身体验证全部结果，单独解码回执不能证明与过去状态一致。

环境入账由事务完成，内存参考已实现。真实宿主须同时协调实体死亡等动作的可靠记录；回执是已提交事实，不是每次读取都要再执行的命令。环境真元后续退化、物品掉落与实际游戏实体仍属后续宿主接入。

## 已实现的经脉运输请求

`body_command.transport_qi` 包含 `connection_id`、`cargo` 和正数 `throughput_per_second`。货物使用既有 `QiInventory`，拒绝空批次、重复相态/属性、非法 ID 和非有限数；不接受客户端在途 ID、到达时间或任意余额。宿主授权需覆盖路线、货物种类/数量和所占吞吐，不能只检查身体归属。

新发运读取宿主可信计划，要求同域同世代、`ACTIVE` 模式且目标时间等于身体已结算游标。游标不同返回 `TIME_REQUIRES_SETTLEMENT`，宿主应先结算、读取新 revision，再以新请求 ID 发运；两步采用同一宿主模拟时刻。冻结模式返回 `TRANSPORT_FROZEN`。发运本身不增加年龄或隐式结算其他事件，原请求重试仍通过成功回执去重。

| 失败类别 | 数值 | 含义 |
|---|---|---|
| `ROUTE_NOT_FOUND` | 15 | 连接不存在 |
| `ROUTE_UNSUPPORTED` | 16 | 端点不是两个不同的经脉/器官模块，例如组织端口 |
| `ROUTE_UNAVAILABLE` | 17 | 任一端经脉破损，或器官/其所属部位失能 |
| `INSUFFICIENT_QI` | 18 | 源内腔/器官缓存缺少所需相态与属性；外壁和加工中输入不能代付 |
| `CAPACITY_EXCEEDED` | 19 | 目标余额与已有预约使剩余容量不足 |
| `THROUGHPUT_EXCEEDED` | 20 | 任一端共享吞吐不足 |
| `TIME_REQUIRES_SETTLEMENT` | 21 | 可信目标与已结算游标不同 |
| `TRANSPORT_FROZEN` | 22 | 当前模式禁止运输、启动加工或组织做功，沿用既有字段名 |

`QiTransit` 新字段 `throughput_per_second = 5`、`status = 6` 均为当前开发契约必填。状态为 `MOVING = 1`、`BLOCKED_ROUTE = 2`、`BLOCKED_CAPACITY = 3`；未知或缺失枚举拒绝，移动中到达须晚于游标，阻塞到达不得晚于游标。旧字段及失败码不复用；同步部署当前契约包，不将未发布开发格式当作兼容承诺。

发运将源扣除、在途记录和回执原子提交，到达由 `advance_time` 原子移除在途并增加目标。阻塞状态可在快照观察；接收条件恢复后显式结算，同毫秒也可完成到达。规则和首轮计算公式见[经脉与真元](Meridian-and-Qi.md)。内存事务参考重算校验候选结果，错误或提交前故障不留下半笔资源变化。

## 已实现的组织与器官意图

`BodyCommand` 新增 `start_organ_recipe = 13` 和 `exert_tissue = 14`；`RequestEnvelope.get_tissue_capability = 12`，对应响应 `tissue_capability = 13`。具体字段、单位和计算规则见[功法扩展](Technique-Addons.md)。三个资源操作（运输、启动加工、做功）均要求可信时间已结算且为 `ACTIVE`，共享授权、版本、去重、私有策略与原子提交路径。

`exert_tissues = 16` 一次提交 1～32 个不同组织的做功需求，先在同一原始快照验算，再合并一次 revision。力可为零，功率和时长必须为正；用于呼吸与注意力等无外部作用力的能耗。`IntakeQi.loads = 3` 可附同类组织成本，与摄入、环境扣账和成功回执一次提交，任何一项失败均不写账。恢复回执时同样复算各组织与摄入结果。

`start_action = 17` 提交动作实例 ID、定义 ID 与版本；`stop_action = 18` 只提交实例 ID。启停要求活动模式与已结算时间，目录决定组织/器官占用，客户端不能上传替代定义。启动回执 `action_definition = 8` 固定完整定义，事务按该事实复算候选身体。权限、私有策略与幂等重试继续生效；宿主应校验玩家是否确实执行或结束相应活动。动作并发、缺包与恢复见[身体动作](Actions.md)。

启动加工成功回执必须包含 `CommandReceipt.organ_recipe = 6`，其 ID/版本与命令一致；事务用该固定定义复算输入扣除和加工事实。完成由时间命令处理，存档中的配方事实足以继续，不重新读取可能卸载的包。这个内部回执字段不能成为客户端注册新配方的入口。

| 新失败码 | 数值 | 原因 |
|---|---|---|
| `MODULE_NOT_FOUND` | 23 | 实际组织或器官不存在 |
| `MODULE_UNAVAILABLE` | 24 | 器官/部位失能，或组织可用耐力为零 |
| `RECIPE_NOT_FOUND` | 25 | 精确 ID/版本未安装 |
| `RECIPE_INCOMPATIBLE` | 26 | 器官类型不符或低于配方最低境界 |
| `ORGAN_BUSY` | 27 | 器官已有未完成任务，包括阻塞任务 |
| `TISSUE_LOAD_EXCEEDED` | 28 | 力量、功率或做功量超过当前组织能力 |

组织基础能力、器官缓存/加工事实的新字段为当前未发布 v1 开发契约的一部分；必填能力和缓存不得缺失。旧开发构建不能读写新格式，所有接入端同步更新，不提供旧快照补默认值迁移。

## 后续操作契约

以下仍是语义草案，**尚未加入 `.proto` 或处理器**，会随对应运行功能一并实现：

| 候选消息 | 方向 | 语义 |
|---|---|---|
| `open_workshop` / `workshop_session` | 请求/响应 | 由服务端签发编辑会话或返回拒绝 |
| `edit_route` / `assign_recipe` | 客户端 → 服务端 | 带请求 ID、会话、目标实例、预期配置 revision 与允许的参数 |
| `activate_recipe` / `stop_operation` | 客户端 → 服务端 | 激发或停止已部署操作，不能提交伤害、余额或“已经成功” |
| `request_result` / `operation_event` | 服务端 → 客户端 | 配置结果、动作接受、完成或中断；这些阶段不能混成一个成功标记 |
| `workshop_closed` | 服务端 → 客户端 | 会话失效及原因，拒绝迟到请求 |

公开客户端投影将按权限裁剪，内部持久化快照与客户端投影不必是同一份载荷。没有已经实现的玩法操作时，不先添加可任意透传的 `bytes` 或 JSON 命令逃生口。

## 后续宿主基础校验

寿元支出已实现授权、版本、剂量、余额、死体、私有策略和原子提交检查。以下描述后续功法、编辑会话及世界资源动作的接入目标，不代表已有装备/知识/材料校验。

服务端统一检查包大小/速率、有限数字、非负剂量、列表与图规模、身份/会话、实例引用、知识、武器、资源、寿命、实际经脉依赖及 revision。客户端预检只用于体验。

当前只使用整个身体的 `revision`；编辑配置的独立 revision 将随编辑器实现。宿主负责限流，不能让无界请求消耗资源。死亡真元释放已有内存事务参考；材料和其他世界输出后续同样需要纳入原子边界，不能在审计回调里执行这些副作用。

## 私有反作弊接口

### 管理员真元调试

`BodyCommand.debug_qi` 是独立授权的调试意图，定义在 `protocol.proto`。普通 `BodyCommandHandler` 构造器默认拒绝；宿主须显式提供 `debugAccess` 谓词，普通授权和提交前策略仍继续检查。身体读写权限不能自动视为调试许可。

`FILL_LUMENS` 空目标表示全身内腔补满；指定经脉可给期望总量，省略则补至稳定体积允许的余额，只补不减。`INJECT_FOR_DIFFUSION` 指定经脉及新增量，后续由既有事件调度沿真实连接扩散。均只新增密度 `0.01` 的游离态无属性真元，保留外壁、伤势、开拓和既有属性明细，扣除所有入向预约后校验体积；活动开脉也不得超出稳定体积。超量、无效或受损目标整笔拒绝。

命令经过同一 revision、去重、私有策略、事务重算和回执持久化。调试明确创造资源，不扣环境库存、不添加 `OpeningIntakeSample`，不能作为持续环境摄入证明，也不直接开启经脉。Human 的 Minecraft 入口要求权限等级 2，核心不依赖 Minecraft 或人体 ID。

### 通用操作卡契约

`operation_ui.proto` 定义 `MeridianOperationUpdate/View/Meter` 和 `MeridianOperationRequest`。展示消息声明目标、阶段、归一化进度与操作入口；请求只提交身份、操作精确版本、目标、预期身体 revision 和动作 ID。UI Addon 按命名空间绑定路由，宿主自行授权和执行所属附属的动作。这些消息不包含脚本，不会让 `BodyCommandHandler` 自动执行未知操作。

| 接口与状态 | 输入 | 输出与限制 |
|---|---|---|
| `BodyCommandAccess.authorize`（已实现调用点） | 宿主可信上下文与已校验命令 | 明确授权操作与剂量后返回可信调用者；empty 拒绝，无默认放行 |
| `BodySecurityPolicy.allows`（已实现调用点） | 宿主可信上下文、命令、只读当前身体 | 核心检查之后接受/拒绝；异常失败关闭，不能改账或消耗材料 |
| `BodyAuditSink.onCommitted`（已实现调用点） | 可信上下文、成功回执、前后不可变快照 | 提交后的尽力通知；重试不重复，异常不回滚；崩溃可能丢失通知 |
| 拒绝/协议审计出口（待实现） | 有界错误类别、连接上下文、限流计数 | 供私有模块观察异常；不向客户端暴露私密策略 |

私有策略在串行事务内同步执行，应保持短小且只读；不得等待另一个需要相同身体锁的任务。需要可靠审计的宿主须把待投递记录纳入事务，不能将此尽力通知当作恰好一次的消息系统。

没有私有插件时，单机和公共服务器使用完整基础校验即可运行。宿主可以声明私有策略为部署必需，此时缺失或故障拒绝相关变更；该要求由服务器配置，不能由客户端关闭。

公共项目只提供接口、调用边界和测试替身，不包含私有检测算法、密钥、下载器或服务地址。单机本地拥有者可修改自己的程序/存档，公共接口不承诺阻止本地篡改；多人服以服务器权威状态为准。
