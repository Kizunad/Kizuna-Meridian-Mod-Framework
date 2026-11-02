# 组织训练成长

组织训练是 Framework 的通用状态能力。生物或功法附属在组织定义中声明 `TissueTraining`，
可信活动再用 `ActivityEffort.training = true` 记录做功；框架不按组织名称猜测哪些组织可以训练。

## 参数与状态

| 字段 | 单位 | 含义 |
|---|---|---|
| `maximum_growth` | 无量纲 | 耐力成长上限，例如 `0.5` 表示最多增加 50% |
| `consolidation_half_life_seconds` | 模拟秒 | 组织空闲时待巩固负荷减半所需时间 |
| `work_joules_per_growth` | 焦耳 | 完整成长 1.0 所需的巩固做功 |
| `training_growth` | 无量纲 | 已巩固成长，保存于身体状态 |
| `pending_training_load` | 焦耳 | 尚未巩固的实际做功，保存于身体状态 |

训练做功同时按普通组织做功规则增加疲劳。能力查询只把有效耐力乘以
`1 + training_growth`；力量和爆发功率保持定义值。

## 巩固时序

`ACTIVE` 模式下，组织没有被任何 `BodyAction` 占用、组织和所属部位仍有 HP 时，经过 `t` 秒后：

```text
remaining = pending × 2^(-t / consolidation_half_life_seconds)
consolidated = pending - remaining
growth += consolidated / work_joules_per_growth
          × (maximum_growth - growth) / maximum_growth
```

成长按上限裁剪；达到上限时清除剩余待巩固负荷。动作占用期间、`LIFESPAN_ONLY` 冻结期间和失能期间
不巩固，之后从保存的负荷继续。没有训练定义的组织必须保持成长和负荷为零。

训练字段是快照与模板 Protobuf 的兼容新增，身体 schema 仍为 3；旧组织解码为零状态。
可信活动的训练标记和 `advance_time` 的巩固在同一身体事务内提交，失败不会发布部分疲劳或成长。

[组织耐力与精力恢复](Tissue-Recovery.md)说明疲劳恢复和动作占用，[基础数据定义](Data-Definitions.md)说明
组织定义与身体状态的所有权。
