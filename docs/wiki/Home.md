# Kizuna's Meridian Mod Framework Wiki

通用身体、经脉与功法扩展框架的设计和接入文档。基础模组负责通用身体运行、权威数据与操作协议；独立 Meridian UI Addon 提供通用身体查看和操作界面，人体模型与构型由 Human 提供，人体专属表现归 Human-UI，功法附属 JAR 提供功法内容。3D 经脉世界规划为独立扩展，宿主负责世界接入。

Framework、Human、UI、Items、Vanilla-Damage、Vanilla-Healing、Human-UI、Progression、Qi-Variants、Vanilla-Humanoid-Threatening、Technique-Framework、Basic-Techniques、Door-Breaker、Combat-UI 与 Kizuna-Players-Animation 按适用端整套必装。拆仓用于职责和代码管理，缺少宿主、效果集成或客户端接线是装配错误；未绑定身体的实体继续原版。运行时必装不改变通用核心与人体内容的源码边界。

`scripts/assembly.json` 是 25 个工作区仓库的统一装配清单，按跨仓构建顺序列出目录、根工程名与模块；`scripts/workspace.gradle` 从清单解析相邻仓库目录和产物路径，settings 与工程脚本都可应用，默认在当前构建根目录的上一级查找，`-PkizunaCodeDir` 整体改指，`-Pkizuna.dir.<清单 id>` 改指单个仓库。同目录的 `scripts/libs.versions.toml` 是 Gradle、Loom、Minecraft、Loader、Fabric API、owo、Protobuf、tomlj 与测试库版本的唯一来源；各仓在 `settings.gradle` 应用 `workspace.gradle` 后即注册为版本目录 `libs`，工程脚本同时取得 `kizunaWorkspace`，Framework 自身也按此取版本。编译所需的相邻仓库在 settings 中用 `kizunaWorkspace.includeSource('<清单 id>', ['<模块路径>': '<替换坐标>'])` 组合构建（根工程模块路径写空串），依赖处按替换坐标声明；清单模块写了 `coordinate` 的（目前是 Framework `fabric` 与 `server`，坐标 `dev.kizuna.meridian:fabric`、`dev.kizuna.meridian:server`）由 `includeSource` 总是追加登记替换（Loom 重映射 fabric 时按真实坐标加回 server），各仓 settings 不必列出，已列出的坐标仍排在前；只在运行时需要的高层仓库产物仍用 `kizunaWorkspace.artifact(...)` 按文件读取，避免低层构建反过来组合高层。`scripts/full-assembly.gradle` 按清单的 `runtime` 字段集中声明开发运行所需产物：Fabric JAR 放入 `modLocalRuntime`；Qi-Variants、功法库、战斗 UI、Debug 等仓的 server/shared/core 纯 Java 库放入 `localRuntime`，因为 Loom 开发重映射不保留安装包的嵌套库；Items 宿主库使用 named 开发 JAR。bs3d 的 Vanilla、Pocket、Weights 附属同时装入 server 与 client JAR，开发环境按 `environment` 只加载适用的一个。同仓源码输出沿用原有排重规则；经 `includeSource` 组合的模块按替换坐标装入，与源码依赖解析为同一组件，不再另读同名文件重复加载；嵌套组合（下游 → Damage → 功法库）由各仓 settings 各自登记、最外层构建汇总整条闭包，所以 settings 只需组合本仓直接依赖的仓库，同一模块以最外层先登记的坐标为准。替换坐标各仓写法一致：模组仓用该工程自身的 group 与工程名（Loom 按真实坐标重新加入模组依赖的传递库），Framework 用 `local.meridian:server` / `local.meridian:client`。替换坐标也可写成列表，第一个用于依赖声明与完整装配，其余只登记替换：按坐标装入的模组经传递依赖带入框架库时，Loom 以框架真实坐标 `dev.kizuna.meridian:server` / `client` 重新加入，只替换占位坐标时它可能按外部模块解析而失败（基础功法 GameTest 即如此），此时把两种坐标都列出。缺少任一装入产物时拒绝启动。先构建各相邻工作区安装产物，再运行开发客户端或 GameTest；加载成功不代表 Human/Threatening 的所有执行效果均已验收。

完整装配回归放在基础功法仓的独立 `gametest` 模块，正式基础功法数据包与测试入口分开加载。经过真实 Human 登录、创造栏、卷轴交互和战斗 UI 自定义载荷入口验证轻步位移、蓄力曲线、劈木门与铁斧耐久、叩门生物实伤；真炁卫道士由原版 AI 自行决策，对在线玩家施放破门斩，并在 `mobGriefing` 开启时劈门。11 项 GameTest 已通过，细节边界继续由各宿主单仓回归保护。

Windows 候选打包入口在战斗 UI 仓 `scripts/package_windows_candidate.py`，按同一份清单的 `candidate` 字段从 25 个当前工作区构建，包含 Framework 的指令注册模组 `meridian_framework`、PA Mobs-Addon、调试库 `meridian_debug`、bs3d 三个附属的客户端 JAR、Burden、Humanoid Dummy 与 Humanoid Taunts，不替换现用 `combat.args`。候选清单保存提交、未提交源码摘要、JAR 哈希与构建日志。客户端烟测使用隔离游戏目录，只核对主菜单、完整加载与 Mixin 错误；结果单独记录，服务端 GameTest 不代表按键、镜头与动画已经实机验收。

> 当前已实现 Java 25 基础数据、[Protobuf 查询与变更](Protocol-and-Security.md)、寿命推进、经脉/器官运输与[被动扩散](Meridian-and-Qi.md)、[组织能力与器官加工](Technique-Addons.md)、宿主事务/安全接口及环境释放的内存事务参考。框架的 client/server 为两端库；`fabric` 模块是 Fabric 模组 `meridian_framework`，只提供 `/meridian` [指令注册点](Commands.md)，尚无正式版本；Human 提供玩家宿主，伤害和治疗由对应 Addon 接线。肌肉真元增益/攻击、连续流等后续能力仍按模块边界推进。

## 阅读入口

| 目标 | 页面 |
|---|---|
| 了解各模块的职责 | [架构](Architecture.md) |
| 查看已实现类型、字段单位与校验边界 | [基础数据定义](Data-Definitions.md) |
| 提供模型、分层和身体对象映射 | [身体展示资源](Body-Presentation.md) |
| 了解寿命和身体数据归谁保存 | [身体与持久化](Body-and-Persistence.md) |
| 理解真元、经脉与成长 | [经脉与真元](Meridian-and-Qi.md) |
| 配置单脉规则、引导、连续适应与完成扩容 | [开脉定义与运行](Meridian-Opening.md) |
| 对接环境来源、供给与吸收/释放明细 | [环境真元交换](Environment-Qi.md) |
| 配置 chunk 区域、浓度供给和方块传播 | [环境区域与配置](Environment-Configuration.md) |
| 配置地形浓度、五行偏好与了解肺炼化 | [地形灵气生成](Terrain-Qi-Generation.md) |
| 制作功法附属 JAR | [功法扩展](Technique-Addons.md) |
| 注册生物/功法包与处理缺包 | [附属包接入](Addon-Integration.md) |
| 查看已实现能力与剩余任务 | [框架能力 TODO](TODO.md) |
| 理解简易操作与规划中的 3D 建造 | [经脉操作与 3D Addon](Inner-World.md) |
| 接入单机或 Bong | [宿主接入](Host-Integration.md) |
| 查看全部指令与登记方式 | [指令](Commands.md) |
| 对接请求、校验与反作弊接口 | [协议与安全扩展](Protocol-and-Security.md) |
| 了解版本、缺包与升级处理 | [兼容性](Compatibility.md) |
| 查看实施顺序和验收条件 | [路线图](Roadmap.md) |
| 了解非商业许可 | [许可与分发](Licensing.md) |
| 维护和发布本文档 | [文档维护](Documentation.md) |

客户端与单机适配目标为 Minecraft 26.2 / Fabric / Java 25，基础操作面板使用 XML/owo；专门的 3D 内景渲染与铺带规划为独立 Addon。两种入口共用真元引导与执行规则。服务端核心实现语言不限，以统一 Protobuf 契约与行为验收接入；Rust 或 JNI 均不是前置要求，每具身体只有一个权威执行方。

项目主页：[Kizuna-Meridian-Mod-Framework](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework)。
