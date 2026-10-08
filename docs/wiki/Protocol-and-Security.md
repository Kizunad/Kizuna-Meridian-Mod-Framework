# 请求协议与安全扩展

状态：**v1 Protobuf 数据契约、Java 编解码和契约测试已实现，尚未发布。** 宿主网络注册、请求处理器、会话、权限与私有安全策略调用仍未实现。

## 唯一协议来源与构建

按照 Kizuna 各框架的惯例，跨模组、跨语言和宿主边界使用 `.proto` 定义消息，通过 `protoc` 生成类型。这里不再使用 TypeBox → JSON Schema 方案，也不手工维护一套 JSON 协议。

| 源码 | 内容 |
|---|---|
| `src/main/proto/kizuna/meridian/v1/body.proto` | 模板、实际构型、独立部位/组织/器官/经脉、端口与连接、六境、真元明细、寿命、时间游标和完整快照 |
| `src/main/proto/kizuna/meridian/v1/protocol.proto` | 快照查询请求、快照响应和公开拒绝类别 |
| `dev.kizuna.meridian.protocol.pb.BodyMessages` / `MeridianMessages` | protoc 生成的 Java 类型，不手改、不提交生成目录 |
| `BodyProtobuf` | 核心模板/快照与生成类型双向转换、独立二进制编解码 |
| `MeridianProtocol` | 请求/响应二进制编解码、版本与方向校验 |

Protobuf 插件固定 `0.9.6`，`protoc` 与 `protobuf-java` 固定 `4.36.2`，与 Inventory UI / Botany 当前工具链对齐。Java 目标为 17。Gradle 自动下载编译器，无需安装系统级 protoc：

```bash
./gradlew generateProto
./gradlew build
python3 "scripts/wiki.py" check
```

构建生成 Java 库 JAR，以及 `build/distributions/kizuna-meridian-mod-framework-0.1.0-SNAPSHOT-proto.zip` 契约包。JAR 包含生成消息类、原路径 `.proto` 和 `META-INF/kizuna_meridian/protocol/meridian-v1.desc`；ZIP 包含 `.proto` 与 `descriptors/meridian-v1.desc`。描述集包含导入与源码信息。`verifyProtocolArtifacts` 检查两份产物内的协议与当前源码一致，并纳入 `check`。

当前产物仍是 Java 库，尚非可安装 Fabric 模组，也没有发布 Maven 坐标。`protobuf-java` 是公开依赖，不在此 JAR 中内嵌；未来 Fabric 打包需要另外验证依赖装载。Rust 接入方可从契约包生成自己的消息类型，共用字段号与二进制规则；Rust 适配和跨语言运行核心尚未实现。

## 已实现的请求与事实

| 消息 | 方向/用途 | 已实现校验 |
|---|---|---|
| `RequestEnvelope.get_body_snapshot` | 调用方 → 权威宿主，查询指定 `body_id` | 协议版本、关联 ID、明确意图、身体标识 |
| `ResponseEnvelope.body_snapshot` | 权威宿主 → 有权读取完整身体的调用方 | 完整身体与所有模块、账户、版本、时序的核心校验 |
| `ResponseEnvelope.failure` | 权威宿主 → 调用方 | 固定公开错误类别，不包含堆栈或私有策略详情 |
| 独立 `BodyTemplate` / `BodySnapshot` 字节 | 内容交换/未来存档后端 | 格式、规模与核心数据一致性；不授予状态覆盖权限 |

`MeridianProtocol.encodeRequest/decodeRequest` 和 `encodeResponse/decodeResponse` 交换整个消息的原始 Protobuf 字节，不外包 JSON、字符串长度或 `writeDelimitedTo` 前缀。每次调用接收一个已经由宿主分帧的完整消息；网络通道和分片策略尚未注册。**1 MiB 是此编解码器的上限，不表示 Minecraft 的任何单包通道可以直接承载 1 MiB；宿主必须遵守实际传输层的更小限制。**

查询 `request_id` 只关联结果，不证明操作者身份、权限或目标归属。宿主必须先认证并授权，再产生完整快照响应；本轮没有提供自动返回快照的处理器。完整快照包含内部结构与寿命，不可当作公开广播投影。请求类型没有上传快照、修改 HP、余额或寿命的入口。

以下使用的是已经生成并可编译的 Java API：

```java
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

var request = MeridianMessages.RequestEnvelope.newBuilder()
        .setProtocolVersion(MeridianProtocol.VERSION)
        .setRequestId("q1")
        .setGetBodySnapshot(MeridianMessages.GetBodySnapshot.newBuilder()
                .setBodyId("body-1"))
        .build();
byte[] payload = MeridianProtocol.encodeRequest(request);
var decoded = MeridianProtocol.decodeRequest(payload);
// decoded 只是通过格式校验的请求；宿主仍需验证操作者是否有权读取 body-1。
```

固定查询字节为 `08011202713152080a06626f64792d31`；相同关联 ID 的 `ACCESS_DENIED` 响应为 `0801120271315a020802`。契约测试固定这两组字节，避免编码与解码同时改错而往返测试仍通过。可独立使用 protoc 校验查询样例：

```bash
protoc --proto_path="src/main/proto" \
  --encode=kizuna.meridian.v1.RequestEnvelope \
  "kizuna/meridian/v1/protocol.proto" <<'EOF'
protocol_version: 1
request_id: "q1"
get_body_snapshot { body_id: "body-1" }
EOF
```

## 数值、存在性与有界解码

- 协议版本、快照 schema、模板内容版本分别记录，当前协议与快照 schema 均只接受 `1`。
- 时间与 revision 使用非负 `int64`，精确保留至 `Long.MAX_VALUE`；真元与其他连续量使用有限 `double`，沿用[基础数据定义](Data-Definitions.md)的单位与机器舍入规则。
- 合法为零的数值与布尔值声明为 `optional`，**v1 业务语义仍要求显式提供**。`0`、`false` 与缺失不同；单值嵌套消息也必填，空账户须显式提供空 `QiInventory`。
- 字符串非空白；所有单字符串最多 1024 个 UTF-8 字节，单列表最多 4096 项，全消息已知字段值最多 65536 个，整个载荷最多 1,048,576 字节，解析深度最多 32。
- 未设置或未知枚举、未选择 oneof、未知字段、超限、截断、非法 UTF-8 和非有限数字全部拒绝。当前精确版本不会默默丢弃未知身体字段；不是任意新版消息都能由旧版读取。
- `parseFrom` 只证明 Protobuf 格式可解码。`BodyProtobuf` 还执行核心构造校验，检查重复 ID、端口引用、状态对应、容量与 HP 上限、重复真元明细和在途时序。禁止把生成 Builder 当成已校验权威对象。
- 不传独立可写的总真元、剩余寿命或总体 HP 比例，接收方从同一明细派生。解码不推进时间、不扣元、不触发生死或执行功法。

字段号与枚举数值固定在 `.proto`，不使用 Java `ordinal()`。移除字段或枚举值时保留 `reserved`；修改必填规则或语义必须调整相应版本，并增加固定样例与拒绝测试。当前 Protobuf 原生单值/oneof 重复字段按其标准合并规则解析，不承诺二进制载荷具有唯一表示；宿主不能用原始字节哈希代替请求 ID 与业务幂等控制。

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

以下属于尚待接入的宿主/执行器职责，不等同于本轮已实现的有界解码与静态数据校验。

服务端统一检查包大小/速率、有限数字、非负剂量、列表与图规模、身份/会话、实例引用、知识、武器、资源、寿命、实际经脉依赖及 revision。客户端预检只用于体验。

配置 revision 与持续运行状态 revision 分开。请求超时不代表未执行，不盲目换 ID 重发；重复请求按明确去重范围返回已记录结果，不重复扣元、延寿或生成物品。过期范围、数值单位和请求限额是发布前必须冻结的协议内容。

## 私有反作弊接口

| 候选接口 | 输入 | 输出与限制 |
|---|---|---|
| `BodySecurityPolicy.check_command` | 宿主认证的操作者、标准化命令、只读身体/装备/会话事实 | 接受或拒绝；不能修改余额或绕过基础规则 |
| `BodyAuditSink.on_committed` | 唯一操作 ID、最终结果、资源/寿命变更摘要与 revision | 只读审计，异常不重做或回滚已提交操作 |
| 拒绝/协议审计出口 | 有界错误类别、连接上下文、限流计数 | 供私有模块观察异常；不向客户端暴露私密策略 |

调用顺序为受限解码 → 身份/会话/幂等 → 核心条件 → 私有策略 → 提交前重验 → 原子提交 → 结果与审计。策略发生延迟时必须再次验证当前状态。

没有私有插件时，单机和公共服务器使用完整基础校验即可运行。宿主可以声明私有策略为部署必需，此时缺失或故障拒绝相关变更；该要求由服务器配置，不能由客户端关闭。

公共项目只提供接口、调用边界和测试替身，不包含私有检测算法、密钥、下载器或服务地址。单机本地拥有者可修改自己的程序/存档，公共接口不承诺阻止本地篡改；多人服以服务器权威状态为准。
