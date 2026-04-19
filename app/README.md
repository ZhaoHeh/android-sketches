# app

应用壳模块，负责提供启动页与实验页入口列表。

## 职责

- 启动应用
- 展示所有被 `SketchRegistry` 发现的实验页
- 为每个实验页渲染标题与简介卡片
- 在用户点击后跳转到对应 Activity

## 不负责的事情

- 不承载具体实验逻辑
- 不直接依赖实验页内部实现
- 不维护硬编码实验页清单

## 关键入口

- `MainActivity.kt`：首页列表渲染与跳转

## 依赖

- `sketch-core`
- `feat-showcase`
- `feat-camerax-aruco`

