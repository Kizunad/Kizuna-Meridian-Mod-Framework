# Kizuna's Meridian Mod Framework Wiki

通用身体、经脉与功法扩展框架的设计和接入文档。基础模组负责通用身体运行、权威数据与操作协议；独立 Meridian UI Addon 提供通用身体查看和操作界面，人体模型与构型由 Human 提供，人体专属表现归 Human-UI，功法附属 JAR 提供功法内容。3D 经脉世界规划为独立扩展，宿主负责世界接入。

Framework、Human、UI、Items、Vanilla-Damage、Vanilla-Healing、Human-UI、Progression、Qi-Variants、Vanilla-Creature-Threatening 与 Kizuna-Players-Animation 按适用端整套必装。拆仓用于职责和代码管理，缺少宿主、效果集成或客户端接线是装配错误；未绑定身体的实体继续原版。运行时必装不改变通用核心与人体内容的源码边界。

`scripts/full-assembly.gradle` 集中声明开发运行所需产物：Qi-Variants 的 server 与 shared JAR 作为纯 Java 库放入 `localRuntime`，Threatening 的 Fabric JAR 放入 `modLocalRuntime`。缺少必装产物时拒绝启动；当前 Threatening 尚无构建产物，脚本中的路径为待实现的装配目标，不代表已有可运行整套分发。

> 当前已实现 Java 17 基础数据、[Protobuf 查询与变更](Protocol-and-Security.md)、寿命推进、经脉/器官运输与[被动扩散](Meridian-and-Qi.md)、[组织能力与器官加工](Technique-Addons.md)、宿主事务/安全接口及环境释放的内存事务参考。框架自身为两端库，没有独立 Fabric 加载入口或正式版本；Human 提供玩家宿主，伤害和治疗由对应 Addon 接线。肌肉真元增益/攻击、连续流等后续能力仍按模块边界推进。

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
| 对接请求、校验与反作弊接口 | [协议与安全扩展](Protocol-and-Security.md) |
| 了解版本、缺包与升级处理 | [兼容性](Compatibility.md) |
| 查看实施顺序和验收条件 | [路线图](Roadmap.md) |
| 了解非商业许可 | [许可与分发](Licensing.md) |
| 维护和发布本文档 | [文档维护](Documentation.md) |

客户端与单机适配目标为 Minecraft 1.20.1 / Fabric / Java 17，基础操作面板使用 XML/owo；专门的 3D 内景渲染与铺带规划为独立 Addon。两种入口共用真元引导与执行规则。服务端核心实现语言不限，以统一 Protobuf 契约与行为验收接入；Rust 或 JNI 均不是前置要求，每具身体只有一个权威执行方。

项目主页：[Kizuna-Meridian-Mod-Framework](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework)。
