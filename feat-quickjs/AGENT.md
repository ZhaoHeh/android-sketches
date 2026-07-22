# AGENT.md

## 模块职责

`feat-quickjs` 是 QuickJS Android 嵌入验证页，不是通用生产级 JS SDK。

## 线程与生命周期约束

- `JSRuntime`、`JSContext` 和所有 `JSValue` 只能在当前执行的工作线程访问
- Kotlin 异步回调只能调用 `completeHostCall` 写入 native 完成队列
- 不得从 scheduler、主线程或 JNI 回调线程直接 resolve/reject Promise
- 新增等待路径时必须同时响应 deadline、cancel 和页面销毁
- native session 必须在 `eval` 返回后才允许 `destroy`

## 接口约束

- JNI 文本继续使用 UTF-8 `ByteArray`，不要退回 `NewStringUTF`
- `android.invoke` 参数和返回值维持 JSON 协议
- 新增宿主方法时必须定义稳定错误码、参数校验、取消行为和验证用例
- 默认保持 `QJS_BUILD_LIBC=OFF`；启用标准库或 module loader 属于独立范围

## 构建约束

- 默认源码来自 `third_party/quickjs` submodule
- 必须保留 `quickjsSourceDir` Gradle 覆盖入口
- 第一版只支持 `arm64-v8a`
- 修改 JNI 类或方法时同步更新 `RegisterNatives` 签名和 consumer rules
