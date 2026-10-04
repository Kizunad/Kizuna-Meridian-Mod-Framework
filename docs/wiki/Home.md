# Kizuna's Meridian Mod Framework Wiki

通用身体、经脉与功法扩展框架的设计和接入文档。基础模组负责身体运行与数据，功法附属 JAR 提供具体内容，宿主负责世界接入。

> 当前为仓库初始化与设计基线阶段。尚无可安装 JAR、已实现公共 API 或正式版本。本文的接口名和目录均为设计建议，不是现有调用方式。

## 阅读入口

| 目标 | 页面 |
|---|---|
| 了解各模块的职责 | [架构](Architecture.md) |
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

框架面向 Minecraft 1.20.1 / Fabric / Java 17，计划使用 XML/owo 配方面板和专门的 3D 内景渲染。推荐共享 Rust 身体核心，供 Fabric 服务端桥接与 Bong 直接调用；桥接与发行平台仍需验证。

项目主页：[Kizuna-Meridian-Mod-Framework](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework)。
