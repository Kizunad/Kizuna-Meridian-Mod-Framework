# 指令

Meridian 的全部服务端指令都挂在 `/meridian` 一个根下，由 Framework 的 Fabric 模组 `meridian_framework` 统一注册。各附属只登记自己的三级节点或观察频道；没安装的附属不会登记，它的节点也就不存在。本页是指令的唯一说明，各附属 Wiki 只链接到这里。

## 注册点

注册点是 `fabric/` 模块里的 `dev.kizuna.meridian.fabric.MeridianCommands`。附属在 `onInitialize` 里调用它，服务器每次建指令树（包括 `/reload`）时由它创建一次根节点并合并全部登记。

| 方法 | 用途 |
|---|---|
| `register(Branch, name, permission, Subtree)` | 在某个二级节点下登记一个三级节点；`Subtree` 收到的是已经建好的三级字面量，只往下添加参数和执行入口 |
| `registerBranchCommand(Branch, permission, Command)` | 让二级节点自身可执行，例如 `/meridian opening`；每个二级节点只能有一个 |
| `registerWatchChannel(channel, permission, WatchChannel)` | 登记 `/meridian watch <channel> on\|off [<targets>]` 的频道，执行时收到开关方向 `WatchSwitch.ON`/`OFF` 与最终目标集合；`watch` 下不接受直接挂子树 |
| `optionalEntity` / `optionalEntities` / `optionalPlayers` | 生成「省略目标作用于自己、指定目标要权限 2」的两条分支，见[可选目标](#可选目标) |

约定：

- **二级节点固定**为 `Branch` 枚举的 9 个：`body`、`qi`、`opening`、`technique`、`humanoid`、`combat`、`progression`、`watch`、`debug`。附属不能另开二级节点。
- **权限在登记时声明**。注册点把它套在三级节点上，并覆盖子树里自设的 `requires`。二级节点和根节点取下属节点的最低权限，所以玩家看不到全是管理指令的分支。`PLAYER = 0` 用于只作用于自己的玩家指令，`GAMEMASTER = 2` 用于指定他人或调试性质的指令。
- **三级节点只有一个归属**。同名三级节点、同名频道、二级节点重复登记执行入口，都在附属初始化时抛异常，异常文案写出完整路径（如 `/meridian qi fill 已由其他附属登记`）。要往别人的节点下加分支，走该节点归属附属提供的公开入口。

## 可选目标

`[<targets>]` 紧跟动作字面量。省略时作用于执行者本人（唯一例外是 `humanoid aggro clear`，见下表），指定他人时要权限 2。三个辅助方法都会先注册省略目标的分支，再注册目标分支：

```java
MeridianCommands.register(MeridianCommands.Branch.QI, "diffuse", MeridianCommands.GAMEMASTER, (node, context) ->
        MeridianCommands.optionalPlayers(node, (parent, targets) -> parent.then(
                Commands.argument("amount", DoubleArgumentType.doubleArg(0.000001))
                        .executes(command -> diffuse(command.getSource(), targets.resolve(command), ...)))));
```

`TargetedTree` 会被调用两次，一次挂在动作字面量上，一次挂在目标参数上。两次拿到的 `targets` 分别解析为执行者和选择器结果。

**歧义规则**：

- 输入正好是某个字面量时，Brigadier 只走这个字面量。例如 `/meridian body status full` 中的 `full` 不会被当成玩家名。
- 可选目标后面跟着单词参数（经脉、组织）时，`x <word>` 两条分支都能完整解析。Brigadier 在两条都成功时取先注册的那条，所以 `x <word>` 总是按省略目标解析，`x <target> <word>` 才是指定他人。即使单词位置写的是一个合法 UUID 或在线玩家名，也是如此。
- 注册点 GameTest 覆盖了这两种写法。
- 补全的已知限制：目标写成玩家名或 UUID，并且已经打出其后的空格时（如 `/meridian qi get Steve `），Brigadier 不再补全下一个参数；写成选择器（`@s`、`@p` 等）时补全正常。执行不受影响。

全部参数都可 Tab 补全：

- 目标用原版选择器。
- 经脉、组织、功法、物种等由归属附属从当前数据提供补全。

## 指令一览

「权限」一列是三级节点的权限；带他人目标的分支一律另要权限 2。

### body

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian body status [<target>]` | 0 | Human | 玩家显示人体动作与数值摘要，末行是负重；目标是类人生物时显示类人身体快照（Threatening 经 Human 的公开入口提供） |
| `/meridian body status [<target>] full` | 2 | Debug（经 Human 的公开入口） | 完整身体明细；超长时写入服务器运行目录下的 `logs/meridian/` |
| `/meridian body tissue <id> [<target>]` | 0 | Human | 单个组织的耐力与恢复；组织名可补全 |
| `/meridian body heal [<targets>] [all\|health\|stamina]` | 2 | Debug | `all` 同时补满真元 |
| `/meridian body heal [<targets>] limb <part> [<amount>]` | 2 | Debug | 只治疗一个部位 |
| `/meridian body revive [<targets>]` | 2 | Debug | 复活已判死的身体 |

### qi

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian qi get [<target>] [<meridian>]` | 0 | Human | 不带经脉时显示全身分账，带经脉时显示该脉内腔与稳定容量 |
| `/meridian qi fill [<targets>] [<meridian> [<amount>]]` | 2 | Debug | 补满或补入指定量的经脉内腔真元；玩家与类人生物都可作目标 |
| `/meridian qi diffuse [<targets>] <amount> [<meridian>]` | 2 | Human | 模拟炼化后入脉并扩散；省略经脉时用人体的扩散入口 |
| `/meridian qi set [<targets>] <amount>` | 2 | Debug | 直接设定真元库存 |
| `/meridian qi set [<targets>] <amount> <meridian\|all> lumen\|wall <density>` | 2 | Debug | 同上，指定经脉、内腔或外壁以及密度 |

### opening

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian opening` | 0 | Human | 显示开脉状态 |
| `/meridian opening start <meridian>` | 0 | Human | 开始开拓一条经脉 |
| `/meridian opening pause` | 0 | Human | 暂停当前开拓 |
| `/meridian opening complete [<targets>] <meridian\|all>` | 2 | Human | 直接完成开拓 |

### technique

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian technique learn [<targets>] <id>` | 2 | Technique | 学会功法；书、卷、式都可补全 |
| `/meridian technique forget [<targets>] <id>` | 2 | Debug | 遗忘功法 |
| `/meridian technique cooldown clear [<targets>]` | 2 | Debug | 清除冷却 |
| `/meridian technique status [<target>]` | 2 | Debug | 已学功法、冷却和最近一次失败 |

### humanoid

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian humanoid spawn <species> [<realm>] [<pos>]` | 2 | Threatening | 物种写实体 ID，补全列出七种内置生人和全部登记物种（含假人 `meridian_humanoid_dummy:dummy`），原版生物可省略命名空间；境界为 `mortal\|wandering_qi\|true_qi`。省略境界时，登记物种用物种表的默认档位，内置生人按自然出生抽档；省略位置时用命令源位置。登记物种可由所属附属提供放置钩子 |
| `/meridian humanoid realm <targets> <realm>` | 2 | Dummy | 改换假人境界；目标不是假人时报错 |
| `/meridian humanoid aggro <A> <B> [mutual\|oneway]` | 2 | Debug | 让 A 与 B 互相（或单向）敌对 |
| `/meridian humanoid aggro clear [<targets>]` | 2 | Debug | 清除敌对链接；链接两端都是生物、不含玩家，所以省略目标时清除全部，不按「作用于执行者本人」处理 |
| `/meridian humanoid taunt <target> [player\|entity]` | 2 | Taunts | 立即说一句垃圾话 |

### combat

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian combat slot set <slot> <technique>` | 0 | Combat-UI | 服务端执行后通知本人客户端写入本地槽位配置；槽位 1–10，功法取已学列表 |
| `/meridian combat slot clear <slot>` | 0 | Combat-UI | 清空槽位 |
| `/meridian combat slot list` | 0 | Combat-UI | 客户端打印本地槽位配置 |

### progression

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian progression unlock [<targets>] [inner_vision]` | 2 | Progression | 解锁进度；带 `inner_vision` 时只解锁内视 |
| `/meridian progression reset [<targets>] [inner_vision]` | 2 | Progression | 重置进度；带 `inner_vision` 时只重置内视 |

### watch

`/meridian watch <channel> on|off [<targets>]`：省略目标时只开关执行者本人，要观察全部实体用 `@e`；`off` 按目标关闭。

| 频道 | 权限 | 登记附属 | 内容 |
|---|---|---|---|
| `body` | 0 | Human | 下肢余力，显示在动作栏 |
| `damage` | 2 | Debug | 伤害明细 |
| `leap` | 2 | Debug | 轻步位移 |

### debug

| 指令 | 权限 | 登记附属 | 说明 |
|---|---|---|---|
| `/meridian debug fatigue set [<targets>] <selection> <ratio>` | 2 | Debug | 设定组织疲劳比例 |
| `/meridian debug fatigue clear [<targets>] <selection>` | 2 | Debug | 清除组织疲劳；清除全部疲劳用 `body heal <targets> stamina` |

## 不在 /meridian 下的指令

以下指令不属于 Meridian 领域，或者只存在于测试源码集，保持原样：

- `/kpa`（客户端）、`/kpa_server`：Players-Animation。
- `/kizuna_anim_mob`：PA Mobs-Addon。
- `/bs3d waist-limit`：bs3d。
- smoke 源码集里的夹具指令。

已删除的旧根，不留别名：

- `/meridian_technique`
- `/meridian_debug`
- `/meridian_combat`
- `/meridian_humanoid_dummy`
- `/meridian_humanoid_taunts`
