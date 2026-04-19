# AGENT.md

本文件面向在本仓库内协作的代码代理或开发者，说明全局改动约定。

## 全局原则

- 保持工程为“实验页聚合器”结构，不要把具体实验实现堆进 `app`
- 新实验模块统一命名为 `feat-*`
- 新实验模块的包名统一放在 `dev.hehe.sketch.feat.*`
- 能复用的发现、排序、跳转逻辑优先放进 `sketch-core`

## 模块接入规则

- 新实验页必须通过 `SketchRegistry` 现有机制被发现
- 不要在首页手写实验页列表
- `manifest` metadata 必须完整填写：标题、简介、排序、模块名
- `dev.hehe.sketch.entry.MODULE` 的值应与 Gradle 模块名一致

## 修改时的注意点

- 修改模块名时，必须同步更新：
  - `settings.gradle.kts`
  - `app/build.gradle.kts`
  - 模块目录名
  - `namespace`
  - Kotlin package
  - 布局中的自定义 View 全限定名
  - `manifest` 中的 module metadata
- 改动 `sketch-core` 的发现机制时，要确认现有 `feat-*` 模块仍能被首页枚举
- 如果引入 CameraX、OpenCV 一类依赖，优先确认与当前 AGP/Kotlin 版本兼容

## 文档规则

- 根目录与每个模块都应同时维护 `README.md` 和 `AGENT.md`
- `README.md` 讲用途、结构、如何使用
- `AGENT.md` 讲约束、边界、修改建议

## 提交信息规则

- 从现在开始，新的 commit message 必须使用 `type(scope): summary` 格式
- `type` 应简洁表达改动类别，例如 `feat`、`fix`、`docs`、`refactor`、`chore`
- `scope` 应指向主要影响范围，例如 `app`、`core`、`feat-camerax-aruco`、`repo`
- `summary` 应使用简短动宾结构，直接说明这次改动做了什么
- 不要再使用不带 `scope` 的自由格式提交信息
