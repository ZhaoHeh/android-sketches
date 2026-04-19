# feat-camerax-aruco

基于 CameraX 与 OpenCV 的 arUco 检测实验模块。

## 当前目标

- 打开页面即启动后置相机预览
- 预览流稳定后开始逐帧检测 arUco
- 在画面上叠加检测框
- 在底部面板显示状态、数量、ID 和耗时

## 当前实现

- CameraX `PreviewView` 提供预览
- CameraX `ImageAnalysis` 提供逐帧分析
- OpenCV `Objdetect` 提供 arUco 检测
- 当前字典使用 `DICT_4X4_100`

## 关键文件

- `CameraArucoActivity.kt`：页面生命周期、权限、OpenCV 初始化、相机会话启动
- `CameraSessionController.kt`：CameraX 绑定
- `ArucoFrameAnalyzer.kt`：逐帧分析与异常兜底
- `OpenCvArucoDetector.kt`：OpenCV 检测逻辑
- `ArucoOverlayView.kt`：检测框绘制

## 当前注意点

- OpenCV native 库必须在创建检测器前成功加载
- CameraX / tracing / Kotlin 版本要与当前工程栈兼容
- 页面底部信息面板采用黑底高对比配色，便于拍摄时阅读

