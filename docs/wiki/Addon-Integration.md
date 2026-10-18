# 附属包接入

状态：已实现当前内容的 Protobuf 清单、整批注册、依赖与能力校验、不可变目录及缺包引用诊断。支持身体模板、组织类型、器官类型、真元属性、固定器官配方、动作及[开拓规则与运行](Meridian-Opening.md)。JAR 发现与加载回调、客户端资源检查、知识/蓝图、迁移与热重载尚未实现；当前仍是宿主调用的 Java 库。

人体包、其他生物包和功法包使用相同入口。核心不内置人体或默认出生数值；纯交互包也可提交没有身体定义的清单。具体配方执行见[功法附属 JAR](Technique-Addons.md)。

## 内容与版本

唯一契约为 `shared/src/main/proto/kizuna/meridian/v1/addon.proto`，生成 Java 类型 `AddonMessages`。跨语言宿主直接生成同一协议，不需要执行 Java 附属代码。

| 消息 | 内容与规则 |
|---|---|
| `AddonBundle` | `schema_version = 1`、清单、类型符号、模板、器官配方、动作、开拓规则；所有定义复用对应公共数据校验 |
| `AddonManifest` | 独占命名空间、正整数包修订、内容 API 范围、必需直接依赖、必要能力、支持宿主列表 |
| `ContentVersionRange` | 含端点的正整数闭区间；必须显式提供上下限，不推断未来版本兼容 |
| `AddonDependency` | 目标包的命名空间与包修订范围，不是模板/配方修订 |
| `ContentSymbol` | `id` 与 `TISSUE_KIND`、`ORGAN_KIND` 或 `QI_ATTRIBUTE`；登记身份，实际数值属于模板/身体 |
| `AddonHostProfile` | 宿主 ID、内容 API 修订、实际支持的定义与运行能力，由可信宿主提供 |
| `AddonRegistration` | `protocol_version = 1`、宿主能力画像、本次完整候选包集合 |
| `ContentReference` | `BODY_TEMPLATE`、`ORGAN_RECIPE`、`ACTION` 或 `MERIDIAN_OPENING_RULE`、ID、精确正整数定义修订 |

包命名空间采用 `[a-z0-9_.-]+`，定义使用 `namespace:path`。包只能发布自己命名空间的内容；`meridian` 为框架保留，内置 `none/metal/wood/water/fire/earth` 六个真元属性身份，未内置任何组织或器官类型。

内容 API 当前为整数修订 `1`，独立于工程的 `0.1.0-SNAPSHOT`、身体 schema `2` 和包修订。Java 参考加载器只接受当前 API 画像；包需要声明包含 `1` 的范围。每个命名空间只安装一个包修订，但同一个模板、配方、动作或开拓规则可以携带多个精确定义修订。`id + version` 重复拒绝，同一 ID 不能同时表示不同种类的内容。已发布的定义修订应保持含义不变，改动语义使用新修订。

模板里的局部部位、经脉和端口 ID 仍只在身体内唯一，不需要逐条注册为全局内容。模板的组织/器官类型、配方的器官类型和输入输出属性必须找到正确种类的符号。跨包引用须声明直接依赖；仅由其他包间接带入目标包不够。框架内置六种属性无需包依赖，自定义属性必须登记。

开拓规则的门槛属性沿用符号检查，引用动作则必须找到 `ACTION` 种类的精确 ID/修订。注册不根据局部目标 ID 猜测具体身体；启动时再核对实际模块。规则字段与意图见[单脉开拓定义](Meridian-Opening.md)。

## 宿主能力

`supported_hosts` 必须非空，只匹配显式宿主 ID，不使用隐含通配符。宿主 ID 由适配方约定；声称支持某个宿主不代表框架已完成该宿主接入。

| 核心能力 ID | 加载器自动要求的内容 |
|---|---|
| `meridian:body_templates_v1` | 包含身体模板 |
| `meridian:organ_recipes_v1` | 包含固定器官配方 |
| `meridian:organ_intake_v1` | 模板中有器官声明 `intake` |
| `meridian:tissue_recovery_v2` | 模板中有组织声明正数 `recovery_half_life_seconds`；按活动动作占用进行指数恢复 |
| `meridian:actions_v1` | 包含动作定义 |
| `meridian:opening_definitions_v1` | 包含开拓规则；仅支持定义校验/登记，不承诺运行推进 |
| `meridian:opening_runtime_v1` | 包清单显式要求的开脉执行能力；Human 已声明，定义登记本身不推导此承诺 |
| `meridian:organ_endurance_v1` | 模板器官声明独立耐力；按动作占用与半衰期恢复 |
| `meridian:passive_qi_flow_v1` | 模板连接声明 `passive_qi_flow`；按事件执行经脉自然扩散 |

这些要求由内容推导，清单漏写也不能绕过。`required_capabilities` 可额外声明宿主条件；每一项都必须出现在可信宿主画像中。能力列表是宿主对自身实现的承诺，不会动态下载算子或创建 Java 回调，更不能由客户端上传获得支持。

## 注册与执行

1. 宿主从管理员安装的附属 JAR 或其他内容来源取得 `AddonBundle` 原始 Protobuf。`AddonProtobuf.decodeBundle` 校验独立文件；当前不扫描文件系统、不自动发现 JAR。
2. 宿主将全部候选与自身画像组成 `AddonRegistration`。跨进程使用 `AddonProtobuf.encodeRegistration/decodeRegistration`；批次仍受 1 MiB、4096 单列表条目及 65536 字段值总量限制，详见[协议上限](Protocol-and-Security.md)。
3. `AddonRegistry.load` 依次检查格式、重复包、API/宿主/能力、依赖存在与修订、循环依赖、定义归属及所有类型引用。任一错误拒绝整批，不跳过坏包继续安装半套内容；已有注册表不受影响。
4. 全部通过后得到不可变注册表。宿主在启动阶段发布它，`findTemplate(id, version)` 提供出生构型，`recipes()` 和 `actions()` 分别作为 `BodyCommandHandler` 第六、第八个参数，第七个参数为宿主环境来源适配。

`openings()` 提供受校验的 `MeridianOpeningCatalog`，作为 `BodyCommandHandler` 第九个参数；开始命令解析可信规则，暂停与后续时间推进使用快照中的固定任务。规则登记时检查动作覆盖全部费用和摄入器官；启动时检查实际构型能力。登记和查询不会自行启动任务。

以下为宿主已有可信完整注册载荷时的实际接入片段：

```java
// 注册载荷来自宿主安装流程；玩家请求不能进入这个入口。
var registration = AddonProtobuf.decodeRegistration(registrationPayload);
var registry = AddonRegistry.load(registration);
var recipes = registry.recipes();
var actions = registry.actions();

// 原有身份、知识、装备条件和私有策略继续约束配方执行。
var commands = new BodyCommandHandler<>(access, security, timeSource, store, audit, recipes, environmentSource, actions, registry.openings());
```

包文件和整个批次必须满足当前有界协议，不能通过直接传生成对象绕过。包内定义非法时沿用原有 `IllegalArgumentException`；包兼容性、依赖或内容引用冲突时抛出 `MeridianErrors.AddonRejected`，`issue()` 返回 protoc 的 `AddonIssue`，包含稳定类别、包命名空间和相关目标。`AddonProtobuf.encodeIssue/decodeIssue` 可交换诊断，不需要解析中文异常文案。当前返回首先发现的问题，修正后重试整个候选。

依赖图使用迭代检查，没有递归深度风险；加载结果不依赖包输入顺序。此处拒绝的是包依赖环，身体里的合法循环经脉仍然合法。目录查询不推进身体、不消耗真元；安装配方也不等于角色已经学习。

`OrganRecipeCatalog`、`ActionCatalog` 和 `MeridianOpeningCatalog` 作为底层目录保留，直接构造只检查定义本身。需要包归属、依赖和缺包诊断的宿主必须从 `AddonRegistry` 安装内容。动作使用的组织/器官是身体局部 ID，启动时与实际身体核对；这些 ID 不隐含所属内容包，依赖特定生物构型的功法作者须显式声明生物包依赖。

## 缺包与存档

宿主可保存 `ContentReferences { protocol_version = 1, references }`，通过 `AddonProtobuf.encodeReferences/decodeReferences` 交换。`registry.inspectReferences` 返回 `ContentAvailabilityReport`，保留每个输入的顺序、种类、ID 和修订：

| 状态 | 含义与处理 |
|---|---|
| `AVAILABLE` | 精确版本已安装，可继续进行角色知识与动作条件校验 |
| `ADDON_MISSING` | 对应命名空间的包未安装；保留引用，提示恢复包 |
| `DEFINITION_MISSING` | 包存在，但没有相应 ID/种类 |
| `DEFINITION_VERSION_MISSING` | 同 ID/种类存在，但该修订缺失；不自动使用最新版 |

报告通过 `AddonProtobuf.encodeAvailability/decodeAvailability` 交换，只反映目录事实。它不是修改知识、清理存档或执行迁移的命令。缺包时已知引用不丢失；重新安装相同内容即可重新查询为可用。实际知识、熟练度和蓝图存储尚未实现。

缺少出生模板时 `findTemplate` 返回空，宿主不能为该模板新建身体。已有 `BodySnapshot` 内嵌实际构型、状态与数值，按保存事实恢复，不重新套用出生模板；组织/器官类型和自定义属性目前只是身份，不是缺失后必须执行的脚本。正在加工的 `OrganProcess` 固定完整配方，因此卸载配方包后仍能按时间完成并结算损耗；新加工请求返回 `RECIPE_NOT_FOUND`。整体测试已覆盖包移除、Protobuf 保存恢复、已成功重试和继续完成。

若保留某个包却卸载它的必需依赖，新注册批次仍会拒绝，需要恢复依赖或一并移除依赖方。已有身体的事实不会因此被目录修改。未来若新增依赖外部运行规则的状态，须同时新增恢复校验；当前格式不支持注入此类脚本。

已启动动作保存完整占用定义，缺包后仍能维持占用、计算恢复和结束实例；新启动返回 `ACTION_NOT_FOUND`。完整规则见[身体动作](Actions.md)。

开脉任务保存完整规则、策略配置、参与组及摄入证据；仅目录规则缺失而原策略仍可用时，可以继续固定任务。策略执行器缺失时安全暂停、解除占用并守恒释放临时超额；新启动缺规则返回 `OPENING_RULE_NOT_FOUND`，缺策略返回 `OPENING_POLICY_UNAVAILABLE`。普通 `stop_action` 不能删除活动开脉持有的动作，须走开脉暂停完成额度撤销与释放。条件由对应附属自己的 Protobuf 与可信策略提供，框架不解释 Any，具体接入见[开脉策略](Meridian-Opening.md)。

首版按启动时安装设计，增删 JAR 需要重启。尚未实现新建身体命令、生命周期热切换、定义迁移、客户端必需资源检查或附属 JAR 发布打包。
