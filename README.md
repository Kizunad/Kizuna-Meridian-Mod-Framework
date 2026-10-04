# Kizuna's Meridian Mod Framework

面向 Minecraft 的通用身体与经脉模组框架。基础模组管理部位、肌肉、器官、经脉、真元、寿命及其存档；功法附属 JAR 通过公开接口提供配方、运行模板、学习来源与表现。

**当前状态：仓库初始化与设计基线阶段。** 本仓库已整理架构、数据归属、扩展契约和实施路线；尚未实现运行核心、Fabric 模组或 JNI 桥接，没有可安装 JAR、稳定 API 或 Maven 发布。文档中的候选接口不是可直接调用的方法。

[Wiki 首页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki) · [本地文档](docs/wiki/Home.md) · [实施路线](docs/wiki/Roadmap.md) · [许可证](LICENSE)

## 目标与边界

- **独立基础模组**：不依赖 Bong 源码，身体与寿命数据由框架统一持有，宿主只通过公开契约接入。
- **功法独立扩展**：基础模组负责运行机制，具体流派、配方和传承内容属于附属 JAR；官方基础功法也走相同接口。
- **单机与服务端**：目标支持 Fabric 单机，并适配 Bong 的 Rust 服务端；同一身体核心提供一致规则。
- **模块化生物**：人体是构型之一，其他生物通过部位、组织、器官、经脉与连接模板组合。
- **3D 内景操作**：玩家在内景铺设精力传送带、调运属性真元、配置器官配方；外界实体仍可受伤，受伤退出编辑。
- **权威存档与校验**：寿命、伤势、局部真元和成长不由客户端或功法包另建账本；额外反作弊只提供服务端扩展接口。

首个适配目标为 **Minecraft 1.20.1 / Fabric / Java 17 / owo-lib**。推荐共享 Rust 核心，Bong 直接链接，Fabric 服务端通过薄 JNI 桥接调用。原生桥接、支持平台与发布依赖尚需最小切片验证；不承诺任意 Java 插件自动在 Rust 服务端执行。

窗口与通用交互参考 [Kizuna's Inventory UI Framework](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework)，框架化边界参考 [Kizuna's Botany Framework](https://github.com/Kizunad/Kizuna-Botany-Framework)。本仓库未复制它们的运行时源码或资源。

## 文档入口

| 关注点 | 文档 |
|---|---|
| 核心、客户端、宿主和附属包的分工 | [架构与职责](docs/wiki/Architecture.md) |
| 寿命、年龄、身体状态与存档所有权 | [身体数据与持久化](docs/wiki/Body-and-Persistence.md) |
| 经脉、真元、五境与成长设计 | [经脉与真元](docs/wiki/Meridian-and-Qi.md) |
| 为框架编写功法附属 JAR | [功法扩展](docs/wiki/Technique-Addons.md) |
| 3D 工厂交互与器官配方 | [内景与编辑器](docs/wiki/Inner-World.md) |
| Fabric 单机和 Bong 的接入边界 | [宿主接入](docs/wiki/Host-Integration.md) |
| 请求、校验和私有反作弊接口 | [协议与安全扩展](docs/wiki/Protocol-and-Security.md) |
| 版本、缺包、升级和发布 | [兼容性](docs/wiki/Compatibility.md) |

## 当前可运行的检查

需要 Python 3.10 或更新版本，无第三方 Python 依赖。

```bash
python3 scripts/wiki.py check
```

该命令检查文档结构与链接，不代表游戏功能通过测试。运行时构建命令将在真实 Gradle/Rust 工程加入后提供。

Wiki 正文唯一编辑来源为 `docs/wiki/`，发布方式见[文档维护](docs/wiki/Documentation.md)。

## 许可

原创代码与文档采用 **[PolyForm Noncommercial 1.0.0](LICENSE)**，只允许该许可证规定的用途。商业用途需另行获得权利人的授权。

这是公开可读的 source-available 项目，不声明为 OSI 开源许可。附属模组、整合包与服务端分发仍须遵守适用条款；第三方内容保留原有许可，见 [NOTICE](NOTICE) 和[第三方说明](THIRD_PARTY_NOTICES.md)。
