# Kizuna's Meridian Mod Framework

面向 Minecraft 的通用身体与经脉模组框架。基础模组管理部位、肌肉、器官、经脉、真元、寿命及其存档；功法附属 JAR 通过公开接口提供配方、运行模板、学习来源与表现。

**当前状态：基础数据定义、Protobuf 编解码与 Java 参考查询处理器已实现。** 提供与宿主无关的 Java 17 不可变身体数据和校验；查询入口由宿主注入可信身份、读取权限与快照来源，返回 Protobuf 响应。身体运行时、实际网络接入、Fabric 模组、持久化适配和功法内容仍未实现。版本 0.x API 会继续通过契约测试和文档冻结。

[Wiki 首页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki) · [本地文档](docs/wiki/Home.md) · [实施路线](docs/wiki/Roadmap.md) · [许可证](LICENSE)

## 目标与边界

- **独立基础模组**：不依赖 Bong 源码，身体与寿命数据由框架统一持有，宿主只通过公开契约接入。
- **功法独立扩展**：基础模组负责运行机制，具体流派、配方和传承内容属于附属 JAR；官方基础功法也走相同接口。
- **单机与服务端**：目标支持 Fabric 单机、Bong 及其他服务端；实现语言不限，通过统一 Protobuf 契约接入，每具身体只有一个权威执行方。
- **模块化生物**：人体是构型之一，其他生物通过部位、组织、器官、经脉与连接模板组合。
- **3D 内景操作**：玩家在内景铺设精力传送带、调运属性真元、配置器官配方；外界实体仍可受伤，受伤退出编辑。
- **权威存档与校验**：寿命、伤势、局部真元和成长不由客户端或功法包另建账本；额外反作弊只提供服务端扩展接口。

首个客户端与单机适配目标为 **Minecraft 1.20.1 / Fabric / Java 17 / owo-lib**。服务端核心可用 Java、Rust 或其他支持该 Protobuf 契约的语言实现，不要求共享 Rust 库或使用 JNI。各实现遵守相同消息语义与行为验收；传输、身份认证及世界接入由宿主提供，Protobuf 本身不执行身体规则。

窗口与通用交互参考 [Kizuna's Inventory UI Framework](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework)，框架化边界参考 [Kizuna's Botany Framework](https://github.com/Kizunad/Kizuna-Botany-Framework)。本仓库未复制它们的运行时源码或资源。

## 文档入口

| 关注点 | 文档 |
|---|---|
| 核心、客户端、宿主和附属包的分工 | [架构与职责](docs/wiki/Architecture.md) |
| 已实现数据类型、字段单位和校验范围 | [基础数据定义](docs/wiki/Data-Definitions.md) |
| 寿命、年龄、身体状态与存档所有权 | [身体数据与持久化](docs/wiki/Body-and-Persistence.md) |
| 经脉、真元、六境与成长设计 | [经脉与真元](docs/wiki/Meridian-and-Qi.md) |
| 为框架编写功法附属 JAR | [功法扩展](docs/wiki/Technique-Addons.md) |
| 3D 工厂交互与器官配方 | [内景与编辑器](docs/wiki/Inner-World.md) |
| Fabric 单机和 Bong 的接入边界 | [宿主接入](docs/wiki/Host-Integration.md) |
| 请求、校验和私有反作弊接口 | [协议与安全扩展](docs/wiki/Protocol-and-Security.md) |
| 版本、缺包、升级和发布 | [兼容性](docs/wiki/Compatibility.md) |

## 当前可运行的检查

文档检查需要 Python 3.10 或更新版本，无第三方 Python 依赖；数据层测试需要本机安装 JDK 17。Gradle Wrapper 使用 8.8，首次运行会下载 Gradle 和测试依赖。

```bash
python3 scripts/wiki.py check

./gradlew test
```

前者检查文档结构与链接，后者检查无 Minecraft 依赖的基础数据层契约。Fabric 运行时构建命令将在接入工程加入后提供。

完整 Java 构建运行 `./gradlew build`，包括查询授权、失败语义与协议产物检查。跨语言查询已完成一次性验证，临时脚本已移除；验证范围与常驻回归边界见[宿主接入](docs/wiki/Host-Integration.md)。

Wiki 正文唯一编辑来源为 `docs/wiki/`，发布方式见[文档维护](docs/wiki/Documentation.md)。

## 许可

原创代码与文档采用 **[PolyForm Noncommercial 1.0.0](LICENSE)**，只允许该许可证规定的用途。商业用途需另行获得权利人的授权。

这是公开可读的 source-available 项目，不声明为 OSI 开源许可。附属模组、整合包与服务端分发仍须遵守适用条款；第三方内容保留原有许可，见 [NOTICE](NOTICE) 和[第三方说明](THIRD_PARTY_NOTICES.md)。
