# feat-quickjs

用于验证 QuickJS-NG 在 Android 中的嵌入、资源隔离和异步宿主桥接。

## 能力

- Kotlin → JNI → QuickJS 全局脚本执行
- 每次运行独立 `JSRuntime + JSContext`
- 后台单线程执行、超时、取消、内存与栈限制
- `console.log` 日志采集
- Promise job queue 驱动
- 通用 `android.invoke(method, args)` 异步协议
- `android.getDeviceInfo()` 和 `android.delayEcho(...)` 示例
- 可编辑 Playground、预置用例与批量报告

QuickJS 核心静态链接到 `libquickjs_bridge.so`。模块只构建 `arm64-v8a`，不包含
QuickJS 的 `std/os/bjson`、模块加载、文件或网络能力。

## 获取 QuickJS

默认源码是仓库根目录的 submodule：

```bash
git submodule update --init --recursive
```

验证另一个 QuickJS checkout 时：

```bash
./gradlew :feat-quickjs:assembleDebug \
  -PquickjsSourceDir=/absolute/path/to/quickjs
```

默认 submodule 固定在 QuickJS-NG commit
`947e6b056c0b0a52c33678aef04560a54c60d61e`。QuickJS-NG 使用 MIT License，
许可证原文位于 `third_party/quickjs/LICENSE`。

## JavaScript API

```javascript
console.log("hello", { answer: 42 });

(async () => {
  const device = await android.getDeviceInfo();
  const echoed = await android.delayEcho({ answer: 42 }, 100);
  return android.invoke("method", { key: "value" });
})();
```

宿主调用参数和结果必须可 JSON 序列化。脚本需要返回或 await 异步调用产生的
Promise；未被等待的宿主任务会在本次运行结束时取消。

## 构建

使用 JDK 11、Android NDK `26.1.10909125` 和 CMake `3.22.1`：

```bash
./gradlew :feat-quickjs:assembleDebug :app:assembleDebug
```
