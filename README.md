# android-sketches

一个以“可发现实验页”为核心的 Android 多模块工程。

首页由 `app` 模块提供，实验页注册与发现逻辑由 `sketch-core` 提供，具体实验能力放在各个 `feat-*` 模块中。当前已经接入：

- `feat-showcase`：最小示例模块，用来验证模块发现链路
- `feat-camerax-aruco`：CameraX 预览 + OpenCV arUco 检测示例
- `feat-arcore`：ARCore 可用性检查与客厅墙面挂画预研入口

## 工程结构

- `app`：应用壳、首页入口、实验页列表展示
- `sketch-core`：实验页发现、排序、跳转的公共逻辑
- `feat-showcase`：示例实验页
- `feat-camerax-aruco`：相机预览与 arUco 检测实验页
- `feat-arcore`：Android ARCore 接入与墙面挂画预研实验页

## 实验页接入机制

实验页不通过硬编码注册，而是通过 `manifest` 中的统一 action + metadata 被首页自动发现：

- action：`dev.hehe.sketch.action.SKETCH_ENTRY`
- metadata title：`dev.hehe.sketch.entry.TITLE`
- metadata summary：`dev.hehe.sketch.entry.SUMMARY`
- metadata order：`dev.hehe.sketch.entry.ORDER`
- metadata module：`dev.hehe.sketch.entry.MODULE`

首页启动后会调用 `SketchRegistry.discover(...)` 收集并排序这些入口。

## 构建信息

- AGP：`7.4.2`
- Kotlin：`1.8.22`
- compileSdk：`34`
- minSdk：`24`
- Java target：`11`

Gradle 运行 JDK 请使用 Azul Zulu JDK 11 LTS。Windows 和 macOS 的下载、安装以及 Android Studio Gradle JDK 配置方式见 [SETUP_SUMMARY.md](./SETUP_SUMMARY.md) 的 “Gradle 运行 JDK” 小节。

## 新增实验模块的建议流程

1. 新建 `feat-*` Android library 模块
2. 在 `settings.gradle.kts` 和 `app/build.gradle.kts` 中接入模块
3. 在模块 `AndroidManifest.xml` 中声明实验页 Activity
4. 添加统一的 sketch action 和 metadata
5. 让页面可直接独立打开，不依赖首页传参
6. 补充该模块自己的 `README.md` 和 `AGENT.md`

## 当前已知约定

- 模块前缀统一使用 `feat-`
- 包名前缀统一使用 `dev.hehe.sketch.feat.*`
- 新能力优先做成独立实验页，而不是直接塞进 `app`
- 首页只负责展示与分发，不承担具体实验逻辑
