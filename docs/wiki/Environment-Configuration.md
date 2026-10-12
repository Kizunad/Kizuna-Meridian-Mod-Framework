# 环境区域、浓度与传播配置

状态：TOML 读取、完整配置 Protobuf、有界校验和纯计算已实现。宿主世界的区域库存、邻接扫描、扩散调度、多人供给分配和交换原子事务仍待接入。本页的速率是宿主计算候选量的规则，实际扣账遵守[环境交换契约](Environment-Qi.md)。

## 配置入口

默认文件位于 `server/src/main/resources/meridian-environment.toml`，构建后进入服务端 JAR 根目录。使用 tomlj 1.1.1 解析标准 TOML；客户端只接收 protoc 消息，不依赖 TOML 解析器或环境计算。

| Java 入口 | 行为 |
|---|---|
| `EnvironmentConfigLoader.defaults()` | 读取 JAR 内的完整默认配置 |
| `EnvironmentConfigLoader.load(Path)` | 读取宿主指定的 UTF-8 文件，将覆盖项合并到默认配置 |
| `EnvironmentConfigLoader.parse(String)` | 解析 TOML 文本覆盖项，适合宿主已有配置读取流程的情况 |
| `EnvironmentConfigProtobuf.encode/decode/validate` | 交换、恢复和验证完整的 `EnvironmentQiConfig` |
| `new EnvironmentQiRules(config)` | 校验配置并建立只读方块系数索引，提供下述纯计算 |

文件路径由宿主选择，例如 `config/meridian-environment.toml`；当前库不注册 Fabric 自动加载入口、不自动创建文件。空文件与省略项沿用默认值；显式给出的文件无法读取、TOML 语法错误、未知键、错误类型或非法数值均报错。文件上限为 256 KiB，重复键由 TOML 解析器拒绝。加载在启动或宿主明确切换规则时执行，不在每个 tick 读文件。

最小覆盖示例：

```toml
[region]
size_chunks = 2

[absorption]
volume_blocks_per_second = 1.0

[diffusion]
speed_blocks_per_second = 1.0
default_block_transmission = 0.10

[diffusion.blocks]
"addon:qi_conduit" = 2.5
"addon:sealed_wall" = 0.0
```

方块 ID 必须写成带引号的 `namespace:path`；按精确 ID 覆盖，其余内置方块值继续保留。加载器不读取 Minecraft 注册表、不按名字后缀推断材质，宿主需要时可根据注册表生成覆盖项。方块状态目前不参与查询，例如门的开关状态使用同一 ID 系数。

## 完整配置契约

消息定义位于 `shared/src/main/proto/kizuna/meridian/v1/environment.proto`。TOML 是本地配置入口，跨进程和跨语言仍交换 Protobuf，不传递一套平行 JSON。宿主接收的是补齐后的完整值，不依赖接收端重新加载默认文件。

| TOML 字段 | Protobuf 字段 / 编号 | 默认及范围 |
|---|---|---|
| `config_version` | `config_version = 1` | 1；独立于身体 schema 和来源 revision |
| `region.size_chunks` | `region_size_chunks = 2` | 1；正 int32，横向正方形区域边长 |
| `absorption.volume_blocks_per_second` | `absorption_volume_blocks_per_second = 3` | 1.0；有限非负，格/模拟秒；0 暂停吸收 |
| `diffusion.speed_blocks_per_second` | `diffusion_speed_blocks_per_second = 4` | 1.0；有限非负，方块边长/模拟秒；0 暂停扩散 |
| `diffusion.default_block_transmission` | `default_block_transmission = 5` | 0.10；范围 `[0, 1)` |
| `diffusion.blocks` | `block_transmissions = 6` | 完整有效表；`BlockQiTransmission` 保存 `block_id = 1` 与 `transmission = 2` |

数值 `optional` 在消息中业务必填，合法的 0 也要显式保存。方块 ID 唯一，系数允许任意有限非负值，包括大于 1；三种空气 ID 必须显式存在且恰好为 1。消息规模、未知字段和文本限制沿用[协议边界](Protocol-and-Security.md)。该消息是可信宿主配置，不能注册成客户端修改世界规则的命令。

## chunk 区域与供给

`size_chunks = N` 表示横向 `N × N` 个 chunk，每个 chunk 的横向尺寸为 `16 × 16` 方块。高度采用宿主世界有效高度，不把 chunk 当成立方格。`regionCoordinate(chunkCoordinate)` 向下取整，X、Z 分别调用；区域身份还必须包含宿主维度标识。

```text
区域 X = floor(chunkX / N)
区域 Z = floor(chunkZ / N)
区域体积 = (16 × N)² × 有效高度
```

例如 N=2、高度 384 时体积是 393216 格；chunk -1 与 -2 同属区域 -1，chunk 0 与 1 同属区域 0。`regionVolumeBlocks(heightBlocks)` 计算完整区域体积；宿主若采用有效子体积，应在浓度计算时明确提供实际体积，并保证两个区域使用相同口径。材料阻力通过传播系数表达，不默认为挖掉一个方块就增加一份灵气。

这里“格”指一个方块体积；“元”沿用真元总量单位，相态和属性不改变加账单位。

```text
浓度 C（元/格） = 区域真实库存 Q（元） / 区域有效体积 V（格）
环境侧吸收上限（元/秒） = C × 有效采集体积速度（格/秒）
```

`concentration(inventory, volumeBlocks)` 与 `absorptionLimitPerSecond(inventory, volumeBlocks)` 从库存直接派生，没有单独可写的浓度字段。默认采集速度为 1 格/秒：浓度 0.2 元/格时上限是 0.2 元/秒，浓度 2 时上限是 2 元/秒。不会给每个区域或生物固定发放 2 元/秒。

宿主还需要按可吸收种类、器官吞吐、身体接收空间、剩余库存及多人共享预算限制本身体额度，再填写 `EnvironmentQiOffer.max_absorption_per_second`。浓度改变时结束旧稳定区间，重新计算供给；不能把起点浓度无限积分，也不能给每个生物重复使用全部共享预算。库存和体积都必须有效，零库存的供给为 0。

## 方块传播默认值

系数是相对空气的无量纲倍率。完整 ID 表在默认 TOML 中，原版未单独列出的方块和未知附属方块均采用 `0.10`，因此原版非空气方块的默认系数都小于 1。以下为常见默认值：

| 方块 | 系数 |
|---|---:|
| air / cave_air / void_air | 1.00 |
| 草、蕨、藤蔓；structure_void | 0.95 |
| 苔藓地毯 | 0.90 |
| 各树种树叶 | 0.85 |
| 雪层、细雪 | 0.80 |
| 雪块、苔藓块、海绵 | 0.65 |
| 水、干草块 | 0.60 |
| 湿海绵 | 0.50 |
| 沙砾 | 0.45 |
| 沙、红沙、灵魂沙 | 0.40 |
| 泥土、草方块、常见木板 | 0.30 |
| 熔岩、泥巴、常见原木、下界岩 | 0.25 |
| 黏土、砂岩、冰 | 0.20 |
| 圆石、凝灰岩 | 0.15 |
| 石头、花岗岩、闪长岩、安山岩、石砖、末地石 | 0.10 |
| 深板岩 | 0.06 |
| 玻璃、玻璃板 | 0.05 |
| 铜块、金块 | 0.03 |
| 铁块、钻石块、绿宝石块 | 0.02 |
| 下界合金块、黑曜石、哭泣的黑曜石 | 0.01 |
| 基岩、屏障 | 0.00 |

void 指宿主认定的无介质空间，使用 `minecraft:void_air` 查询；未加载区域不能据此推定为空气或无穷资源。`minecraft:structure_void` 是另一种实际方块。附属特殊方块可以覆盖为 2.5 等增强系数；覆盖同样适用于宿主希望修改的原版非空气方块。只有三种空气基准锁定为 1。

## 边界传播速率

`contactTransmission(firstBlockId, secondBlockId)` 合成相邻两格的系数。两格各贡献半段阻力，使用调和平均；任意一格为 0 都阻断：

```text
k接触 = 2 / (1/k左 + 1/k右)
```

空气与空气为 1；石头与石头为 0.1；空气与石头约 0.1818；两个 2.5 特殊方块仍为 2.5。较低阻力不会凭空增加资源。宿主应对每个边界接触面分别计算并累加，或使用按接触面积加权的平均系数；不是只查看一个方块代表整条边界。

`candidateDiffusionPerSecond(sourceC, targetC, area, transmission)` 使用正浓度差：

```text
候选流量（元/秒） = max(0, C源 - C目标)
                  × 基准速度（方块边长/秒）
                  × 接触面积（方块面数） × k接触
```

浓度差 1 元/格、接触面积 10 个方块面、基准速度 1 时，空气边界候选为 10 元/秒，石头为 1 元/秒，2.5 增强边界为 25 元/秒。方向反转、相同浓度、零面积或阻断时返回 0。无限值、负值与运算溢出会被拒绝。

这些是候选速率，尚未操作账本。正式推进要按相态/属性保持守恒，并统一限制多个邻居的源区总支出。一对隔离区域的净转移不能越过平衡量 `(C源 - C目标) / (1/V源 + 1/V目标)`；多邻区同时结算还需共同分配并重新判断平衡，不能把每对独立结果直接扣账。状态变化和低频事件决定推进时刻，静止区域可停止调度。

## 安装与验证

宿主加载配置后持有一个 `EnvironmentQiRules`。配置不写入身体快照，也不会修改 `InMemoryBodyStore` 的旧单环境账户。日后切换配置时，宿主须先结算旧规则区间，再更新来源版本；改变区域尺寸还需要重分账本，不能只重新算区域坐标。当前没有自动热重载。

`EnvironmentConfigurationTest` 保留配置文件到 Protobuf 往返、非法配置拒绝、负 chunk 坐标、浓度驱动既有交换额度、屏障阻断和大于 1 系数的整体回归。完整构建还校验客户端/服务端 JAR 与协议包使用同一份 schema 和描述集。
