# sketch-core

实验页注册发现的公共核心模块。

## 职责

- 定义实验页 action 与 metadata key
- 通过 `PackageManager` 发现当前应用内已注册的实验页
- 将实验页信息整理成 `SketchEntry`
- 提供统一的打开入口

## 关键文件

- `SketchRegistry.kt`：发现与打开逻辑
- `SketchEntry.kt`：首页展示所需的数据结构

## 设计说明

实验页发现依赖 `manifest`，而不是中心化配置文件。这样每个 `feat-*` 模块只要注册自己的 Activity 和 metadata，就能自动接入首页。

