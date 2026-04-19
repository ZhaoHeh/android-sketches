# AGENT.md

## 模块职责

`feat-camerax-aruco` 只负责“相机预览 + arUco 检测”这一条实验链路。

## 修改建议

- 创建 `OpenCvArucoDetector` 之前，必须先完成 OpenCV 初始化
- CameraX 相关改动优先保持预览和分析链路解耦
- UI 改动时，优先保证拍摄场景下的可读性和对比度
- 如果修改字典类型，记得同步更新 README 与实际需求说明

## 高风险点

- Activity 属性初始化阶段直接 new OpenCV 检测器
- 升级过新的 CameraX / tracing 版本导致与当前 AGP/Kotlin 不兼容
- 改坏 `PreviewView` 与 `ImageAnalysis` 的绑定顺序

## 后续扩展建议

- 如果后续增加更多相机实验，优先新建独立 `feat-*` 模块
- 不要把多种无关视觉实验继续堆进当前模块
