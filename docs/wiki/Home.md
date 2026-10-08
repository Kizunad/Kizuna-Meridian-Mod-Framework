# Kizuna's Meridian Mod Framework Wiki

通用身体、经脉与功法扩展框架的设计和接入文档。基础模组负责身体运行与数据，功法附属 JAR 提供具体内容，宿主负责世界接入。

> 当前已实现无宿主依赖的 Java 17 基础数据定义、校验、[Protobuf 通信契约与参考查询处理器](Protocol-and-Security.md)，尚无可安装 Fabric 模组或正式版本。查询权限与快照来源由宿主注入；玩法运行、实际连接认证、网络与存储接入仍未实现。

## 阅读入口

| 目标 | 页面 |
|---|---|
| 了解各模块的职责 | [架构](Architecture.md) |
| 查看已实现类型、字段单位与校验边界 | [基础数据定义](Data-Definitions.md) |
| 了解寿命和身体数据归谁保存 | [身体与持久化](Body-and-Persistence.md) |
| 理解真元、经脉与成长 | [经脉与真元](Meridian-and-Qi.md) |
| 制作功法附属 JAR | [功法扩展](Technique-Addons.md) |
| 理解内景建造与器官配方 | [内景与编辑器](Inner-World.md) |
| 接入单机或 Bong | [宿主接入](Host-Integration.md) |
| 对接请求、校验与反作弊接口 | [协议与安全扩展](Protocol-and-Security.md) |
| 了解版本、缺包与升级处理 | [兼容性](Compatibility.md) |
| 查看实施顺序和验收条件 | [路线图](Roadmap.md) |
| 了解非商业许可 | [许可与分发](Licensing.md) |
| 维护和发布本文档 | [文档维护](Documentation.md) |

客户端与单机首个适配目标为 Minecraft 1.20.1 / Fabric / Java 17，计划使用 XML/owo 配方面板和专门的 3D 内景渲染。服务端核心实现语言不限，以统一 Protobuf 契约与行为验收接入；Rust 或 JNI 均不是前置要求，每具身体只有一个权威执行方。

项目主页：[Kizuna-Meridian-Mod-Framework](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework)。
