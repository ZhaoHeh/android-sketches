# AGENT.md

## 模块职责

`sketch-core` 是实验页注册发现机制的唯一核心来源。

## 修改建议

- 保持 action 与 metadata key 稳定，避免无必要破坏已有模块
- 如果要新增 metadata 字段，优先保证旧模块仍可兼容
- 排序规则变更时，要确认首页展示顺序是否仍符合预期

## 高风险改动

- 修改 `ACTION_SKETCH_ENTRY`
- 修改 metadata key 常量
- 修改 `discover(...)` 的过滤条件

这些改动会直接影响所有 `feat-*` 模块的可发现性。

