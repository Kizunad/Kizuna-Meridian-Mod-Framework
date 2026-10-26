# 能力修正推进基准

## 运行方式

在 Framework 根目录运行：

```bash
./gradlew :server:capabilityBenchmark --console=plain
```

独立 `benchmark` 源集复用整体回归中的非人体构型及策略，不进入默认 `test`、`check` 或分发 JAR。任务强制 Java 17，固定 512 MiB 初始和最大堆，默认采用 JVM 常规后台编译。只计一次 `BodyTimeAdvancement.apply`；构型生成、计划生成、结果检查和存档对照均在计时之外。结果存入 volatile 引用，避免计算被消除。

日常组每种模式预热 100000 次、采样 501 次；最坏组每种模式预热 200 次、采样 51 次。输出 CSV 包含中位数、p95、最大值及其一基采样序号 `max_sample`，单位毫秒；统计包含全部采样。可用相同参数在两份代码上独立复测：

```bash
./gradlew :server:capabilityBenchmark -PbenchmarkScenario=opening -PbenchmarkWarmup=200 -PbenchmarkSamples=101 --console=plain
./gradlew :server:capabilityBenchmark -PbenchmarkScenario=opening -PbenchmarkJfr="/tmp/meridian-capability.jfr" --console=plain
```

`benchmarkWarmup`、`benchmarkSamples` 只调整最坏组；`benchmarkScenario` 可选 `daily-none`、`daily-1x2`、`daily-1x6`、`daily-4x8`、`same`、`spread`、`crossings`、`opening`。`benchmarkBatchCompile` 显式开启 `-Xbatch`，用于复现同步编译的等待；`benchmarkJfrSettings` 可指定 JFR 配置文件，默认 `profile`。JFR 采样会产生额外开销，性能表使用未开启 JFR 的运行。数据比较须列明 JDK、预热及 JVM 参数。

## 输入与验收范围

所有组从身体时钟 1000 ms 推进到 1600 ms，老化倍率为零，分别测 `ACTIVE` 与 `LIFESPAN_ONLY`。组织初始有疲劳，未占用组织实际执行恢复。

- 日常组：无修正、1 条 2 点、1 条 6 点、4 条 8 点。
- `same`：同一组织 32 条，每条 16 点；第 i 条从 `1000 + i` ms 生效，第 j 点 offset 为 `32 × j` ms，共 512 个交错绝对边界。曲线值交替为 0、1，四项峰值均为 1.01。
- `spread`：相同曲线分散到 32 个组织，每个组织恰有一条；新增组织不复制端口身份。
- `crossings`：同组织 32×16，三项能力峰值 1.01，恢复峰值 3。负点为 −0.475；按点序号与修正序号的奇偶交替，前半段正点为 1、后半段为 0.5。乘积实际越过总倍率上下限，段内存在截断交点。
- `opening`：`same` 加 50 W 连续组织负载，目标内腔填满，任务处于 `HOLDING`，实际累加 600 ms 适应进度；结果不符合这一条件时基准失败。

数值使用[默认 JVM 边界配置](Technique-Addons.md#已实现的组织能力)。这测的是一次跨越全部曲线点的补算；日常组同样跨完各自全部点。更大的目标时间与接近 long 上限的时钟由终止性整体回归覆盖。验收指标为预热后中位数，计时仍可能受 GC、操作系统调度及 CPU 频率影响。

## 结构优化记录（同步编译配置）

2026-10-26，在同一台 i5-13490F 主机的 8 vCPU 虚拟机上，使用 Java `17.0.18+9-LTS`、旧基准配置 `-Xbatch`、最坏组预热 100 次，先运行结构优化前基线，再运行结构优化后的实现，均未开启 JFR。基线从 v2 备份恢复到独立临时目录，只补相同基准入口和测试构型入口；生产工作区没有回退。以下历史记录保留原参数，当前默认配置及尾样本定位见下一节。

下表单位均为毫秒。ACTIVE 的三组能力最坏输入中位数均低于 5 ms，实际维持中的开脉低于 20 ms；日常两种模式的中位数均降低。

| ACTIVE 输入 | 前中位数 | 后中位数 | 前 p95 | 后 p95 | 前最大值 | 后最大值 |
|---|---:|---:|---:|---:|---:|---:|
| 无修正 | 0.004132 | 0.001885 | 0.005322 | 0.002022 | 0.033272 | 0.049514 |
| 1×2 | 0.009392 | 0.002733 | 0.012748 | 0.002975 | 0.066418 | 13.654644 |
| 1×6 | 0.028923 | 0.005797 | 0.042141 | 0.006285 | 0.221367 | 0.028918 |
| 4×8 | 0.169882 | 0.037095 | 0.231362 | 0.052336 | 1.629409 | 0.328469 |
| 32×16 同组织 | 6.734617 | 1.735006 | 8.413004 | 2.078629 | 11.309360 | 2.830582 |
| 32×16 分散 32 组织 | 14.770511 | 2.663502 | 16.967647 | 3.419204 | 19.948389 | 7.481699 |
| 32×16 截断交点 | 8.583018 | 2.087065 | 9.352791 | 2.425700 | 10.182166 | 2.935814 |
| 开脉维持＋512 点 | 1005.355426 | 11.002694 | 1039.178560 | 15.396012 | 1054.735397 | 216.137129 |

| 冻结输入 | 前中位数 | 后中位数 | 前 p95 | 后 p95 | 前最大值 | 后最大值 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 无修正 | 0.001775 | 0.001530 | 0.002016 | 0.002067 | 0.017007 | 0.062364 |
| 1×2 | 0.001924 | 0.001506 | 0.002401 | 0.002342 | 0.025694 | 0.028268 |
| 1×6 | 0.001631 | 0.001332 | 0.001808 | 0.001457 | 0.017135 | 0.017513 |
| 4×8 | 0.001855 | 0.001366 | 0.002098 | 0.001443 | 0.020506 | 0.002759 |
| 32×16 同组织 | 0.001925 | 0.001810 | 0.001974 | 0.001941 | 0.001977 | 0.002536 |
| 32×16 分散 32 组织 | 0.004430 | 0.002765 | 0.004521 | 0.003603 | 0.006461 | 0.015788 |
| 32×16 截断交点 | 0.001958 | 0.001654 | 0.002058 | 0.001819 | 0.002827 | 0.003994 |
| 活动开脉冻结 | 0.008308 | 0.004254 | 0.014755 | 0.955456 | 0.336324 | 1.313732 |

上表保留全部尾部样本：同步编译配置下分散组最大值约 7.48 ms，开脉约 216 ms。后续定位确认开脉固定尾样本来自同步 JIT 编译等待，下面给出证据及基准修正后的三次数据。

## 开脉固定尾样本定位

同主机、同 Java 17、同输入，旧默认命令连续三次稳定出现 220–240 ms 的最大值。单独开启 JFR 的定位运行捕获了第 3 个采样：`apply` 墙钟耗时 268.970 ms，结束仍为 `HOLDING`、`heldMillis=600`。其余 50 个采样也保持相同状态和进度。

`jdk.Compilation` 表明，样本开始后 14.336 ms，C2 开始编译 `BodySnapshot` 的完整构造器，耗时 254.424 ms，生成 82312 字节机器码；编译结束至采样结束仅约 0.211 ms。调用路径是 `BodyTimeAdvancement.apply → BodyAdvancement.result → new BodySnapshot`，对应整次推进末尾的一次完整校验。该窗口没有 GC、GC 暂停或反优化事件。稳定长尾来自 `-Xbatch` 关闭后台编译后，调用线程同步等待编译完成。

只将预热提高到 200 次仍不足以排除同步编译：另一次细化记录的第 4 个样本耗时 46.171 ms，窗口内有 96 条编译事件，合计 25.555 ms，其中 87 条是 Protobuf 校验使用的反射访问器。第 21 个样本还触发了 `BodyProtobuf.toMessage` 及其 builder 的编译。这些是单次推进初始化投影时的编译，不是拐点触发反复全量投影。

修正仅涉及基准：默认采用服务器常规后台编译，最坏组预热提高到 200 次；保留显式同步编译开关与全量样本，不剔除、重试或替换慢样本。核心推进、策略、精度断言、确定性和固定积分次数均未改变。JFR 增加 `dev.kizuna.meridian.AdvanceSample` 事件，包含场景、模式、`phase`、采样序号、任务状态和进度；预热整体使用 `phase=warmup`，采样使用 `phase=sample`，计时和事件提交相互分离。

使用修正后配置的另一轮 JFR 记录中，快照构造器的 C2 编译耗时 281.756 ms，发生在开脉预热期间，结束于首个采样前约 1.564 秒。51 个采样均保持 `HOLDING/600 ms`；最慢为第 41 个，16.818 ms，其窗口没有编译、GC 或反优化事件。

以下为两种基准配置各连续三次完整运行，均未开启 JFR；只调整了上述基准设置。单位为毫秒，测量输入均为实际开脉维持、512 个交错拐点。

| 次数 | 旧中位数 | 旧 p95 | 旧最大值 | 新中位数 | 新 p95 | 新最大值 | 新最大值序号 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.922585 | 18.144582 | 239.844341 | 12.439253 | 14.236506 | 18.448452 | 45 |
| 2 | 11.200567 | 15.528717 | 233.148915 | 12.794644 | 14.138265 | 17.442461 | 3 |
| 3 | 11.886937 | 16.876593 | 220.766603 | 12.667077 | 14.726905 | 15.569107 | 5 |

可用完整场景顺序复现旧编译等待并记录全部编译事件；单独选择 opening 会改变先前的类型分支和 JIT 配置历史，不能视为同一次序的复现：

```bash
jfr configure --input profile --output "/tmp/meridian-compiler.jfc" "jdk.Compilation#threshold=0 ms"
./gradlew :server:capabilityBenchmark -PbenchmarkWarmup=100 -PbenchmarkBatchCompile \
  -PbenchmarkJfr="/tmp/meridian-tail.jfr" -PbenchmarkJfrSettings="/tmp/meridian-compiler.jfc" --console=plain
jfr print --events "dev.kizuna.meridian.AdvanceSample,jdk.Compilation,jdk.GarbageCollection" \
  "/tmp/meridian-tail.jfr"
```

修改后直接运行默认命令即可重复测量。以上最大值是本机三次全部样本的实测结果；真实服务器的冷启动编译、GC 和操作系统调度仍应按部署配置测量，不能从有限次采样推出硬实时保证。

## 结构与热点

`BodyAdvancement` 在单次调用内保存活动候选，按需建立并复用组织、部位、状态位置与曲线索引。全身边界一次排序去重；同组织的曲线插值、三项能力乘积、恢复端点和相同区间的最低倍率共用有界缓存。恢复仍逐边界计算指数衰减，不合并多个指数，因此原有分段精度断言与容差保持不变。

内部切段不构造核心 `BodySnapshot`；整次推进结束才构造并全量校验一次，revision 只加一。普通冻结直接结算一个区间，复用相同的寿命、资源平移、死亡与到期规则，不建立活动上下文。活动开脉冻结先解除动作占用。不可变空真元账户共用已校验实例，空入账直接复用原账户；空明细、单明细求总量和体积也不建立 Stream。索引仅在身份缺失时构造错误文案，正常路径不分配错误文本。

开脉的同进程 SPI 直接传递生成消息，策略上下文复用未变化的规则、任务计划和库存子树。只替换时间、疲劳、进度、收据及发生变化的资源；不缓存裁定，不减少策略调用次数，每次返回值仍完整校验。字节适配保留请求和响应的完整协议校验，Java 实现迁移说明见[策略契约](Meridian-Opening.md#可信策略契约)。

恢复积分保留固定 17 点 Gauss–Legendre、固定 64 次求根和 `StrictMath`。查询、积分及求根的正常乘积使用 double；求根只对完整乘积取一次对数。中间上溢或进入非规格化范围时，仍重新累计全部因子的对数，避免反向因子抵消丢失。做功区间终点仍使用 BigDecimal，保留小数毫秒与接近 long 上限时的精度；相同区间预算复用最低倍率缓存。

## 压力测试与单次耗时的区别

固定种子 `20261026` 的 48 场景整体回归，每个场景同时运行一次整段推进及按每个输入边界逐段推进，再比较完整状态和释放账。逐段路径会为每次调用重建上下文并校验最终快照；其累计耗时不是一次 `apply`。场景还覆盖常值、起止阶跃、重合拐点、当前和目标边界、冻结、活动开脉、途中寿尽及极端时钟。

结构优化交付时的默认 `./gradlew test build --console=plain` 共执行 215 项测试，失败、错误和跳过均为零。终止性测试总耗时 536 ms，最慢场景 180 ms，最大推断外层迭代数 512，满足每个输入的「曲线点总数＋1」上界。使用已有 JUnit 4：整体 15 秒超时，每场景通过单个守护线程执行并在 `Future.get` 限时 2 秒；不新增 Jupiter 依赖或许可条目。

旧的秒级压力场景包含上述对照成本；独立探针在普通时钟下仍测出开脉生产路径的秒级开销，因此不能全部归因于测试或极端时钟。主要热点是每个边界重复构造并全量校验快照，以及策略完整编码、解析和递归字段校验。当前整体回归继续逐字段对照，不放宽恢复的 `1e-9` 容差；最大外层切段数由输入边界独立推断，生产代码没有测试计数器。

## 整体回归覆盖

以下能力规则由 `CapabilityModifiersTest` 覆盖；固定倍率与变值曲线经过相同路径。

| 规则 | 测试方法 |
|---|---|
| 乘积后截断上下限、组织隔离 | `productsClampAfterAllFactorsAndAffectOnlyTheirTissue` |
| 任意时刻插值、负值、固定倍率起止阶跃 | `curvesInterpolateAtArbitraryTimesIncludingNegativeValues`、`constantCurvesHaveStartAndExpirySteps` |
| 点数、offset、时长、峰值、最低倍率、非有限数拒绝 | `invalidCurvesAndPointMultipliersRejectAtomically`、`invalidMultipliersRejectTheWholeActivityInEveryDimension` |
| 非法 ID、组织、起止时钟 | `invalidIdentityTissueAndExpiryRejectTheWholeActivity` |
| 重复 ID、每身 32 条、整笔回滚 | `duplicateIdsAndTheBodyLimitAreAtomic` |
| 同 source 撤销、不存在或重复 ID 拒绝 | `modifierSourceIsBoundToTheTrustedCallerForAddAndRemove`、`removalRequiresAnExistingUniqueIdAndCannotReplaceIt` |
| 查询与力量、功率、做功预算共用有效能力 | `queryAndExertTissueUseModifiedHealthAndFatigue`、`effectiveLimitsRejectExcessForcePowerEnergyAndRemovedBoosts` |
| 活动内增删修正先于做功、失败原子回滚 | `activityEffortsUseAddedAndRemovedModifiersAndRollbackOnFailure` |
| 做功检查拐点及阶跃两侧、保留大时钟的小数毫秒 | `exertionUsesTheMinimumAcrossKnotsAndBothStepDirections`、`exertionKeepsFractionalMillisecondsAtLargeClockValues` |
| 开脉预算与到期切段 | `expirationSplitsContinuousWorkLikeTwoAdvances`、`independentBurstAndEnduranceModifiersSetTheContinuousBudget` |
| 恢复在阶跃、上下限交点、内峰和最高次数下整段与拆段一致 | `recoveryConstantStepsMatchSingleAndPartitionedAdvances`、`recoveryIntegratesProductsWithUpperAndLowerClamps`、`recoveryHandlesInteriorPeaksAndTheMaximumPolynomialDegree` |
| 占用、失能、冻结停止恢复 | `recoveryModifiersRespectOccupationDisabilityAndFrozenTime` |
| 精确到期、冻结到期、死亡清空 | `expirationRunsAtTheExactBoundaryAndWhileFrozen`、`lifespanAndHealthDeathsClearModifiers` |
| 快照、回执与普通操作保留修正 | `snapshotsReceiptsAndOrdinaryOperationsPreserveModifiers` |
| 极长时间与满载随机场景终止 | `hugeTimeIntervalsRemoveCurvesWithoutPerSecondIteration`、`randomizedFullModifierBodiesAlwaysTerminateWithinTheirCurveBoundaries` |
| 增量策略消息与存档后拆段的完整可见状态一致 | `openingPoliciesSeeIdenticalSnapshotsDuringWholeAndSavedSegmentAdvances` |

`HealthHostExertionTest` 的 `exertionAcceptsOnlyBatchExertionWithMatchingRequestId` 覆盖命令限制及内外请求身份一致；`hostReturnsCapacityRejectionWithoutChangingFatigue` 覆盖失败码及回滚；`hostExertionUsesTheWholeIntervalInsteadOfTheCurrentBoost` 覆盖区间最低倍率；`successfulRetriesReturnCurrentSnapshotWithoutChargingTwice` 覆盖重试幂等。

`MeridianOpeningContractTest.generatedPolicyMessagesRetainTheValidatedByteAdapter` 保证生成消息与字节入口裁定一致，并继续拒绝非法版本、延迟、收据窗口及未知字段。`QiInventoryTest` 的流转回归同时检查空入账保留低于流转单位的真实余额，混相与混属性总量、体积及数值拒绝保持不变。

## 是否改用数学库

Commons Math 和 Hipparchus 都能提供 Gauss–Legendre 求积与一维求根组件，但不能代替曲线边界、起止阶跃、乘积内峰和截断交点的业务处理。当前节点与权重只初始化为固定常量，积分阶数足以覆盖最多 32 个线性因子的乘积；求根也已有固定的计算上界。

库能减少常量表和通用求根代码，适合以后需要更多阶数或算法时统一使用。但采用它们仍须确认数学函数实现、节点生成与停止条件符合 `StrictMath`、固定次数和现有精度要求；默认的误差阈值或自适应终止方式不能直接替换当前规则。结构优化后没有必要为这些有限代码新增依赖。本次没有引入这两个库，也没有声称或测试它们更快。
