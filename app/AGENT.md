# AGENT.md

## 模块职责

`app` 只做应用壳和首页分发。

## 修改建议

- 新实验页不要直接在 `MainActivity` 里写死
- 需要展示实验页时，优先通过 `SketchRegistry.discover(...)` 获取数据
- 首页 UI 可以调整，但不要破坏“按 manifest 自动发现”的机制

## 不建议的改动

- 不要把实验页 Activity 放进 `app`
- 不要在 `app` 内复制 `sketch-core` 的发现逻辑
- 不要让 `app` 持有某个 feat 模块的强耦合业务逻辑

