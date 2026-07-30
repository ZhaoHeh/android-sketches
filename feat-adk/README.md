# feat-adk

Google ADK Kotlin `0.6.0` 的通用 Android Agent 实验模块。它不是某个固定工具 Demo，
而是用动态能力目录把 ADK Agent 接到进程内的 MCP 风格端点：

```text
chat → ADK LlmAgent → AdkMcpToolset → AgentCapabilityRegistry
                                      ├─ QuickJS MCP endpoint
                                      └─ Android system MCP endpoint
                                          ├─ calendar_create_event
                                          └─ timer_set
```

## 当前能力

- `quickjs_execute`：在 `feat-quickjs` 的隔离运行时中执行模型生成的 JavaScript；2 秒、
  16 MiB 堆、512 KiB 栈限制，不开放网络、文件、JNI、Context 或 `android.invoke`。
- `calendar_create_event`：用户确认后再申请日历权限，选择 primary 或首个可写日历，
  通过 `CalendarContract.Events` 插入事件。
- `timer_set`：用户确认后发送 `AlarmClock.ACTION_SET_TIMER`，返回“已派发”，不虚构
  系统 Provider 状态。

`McpEndpoint`、descriptor、JSON Schema、annotations 和结构化 result 是模块内稳定边界。
当前端点是进程内调用，不宣称实现 MCP JSON-RPC 或网络互操作；以后可在不改 Agent 层的
前提下增加 Streamable HTTP endpoint。

## ADK 审批链路

日历和计时器使用 `ToolContext.requestConfirmation()`。首轮只产生
`adk_request_confirmation`，UI 逐项展示允许/拒绝；允许后发送对应
`FunctionResponse` 恢复原调用。相同 function-call ID 的系统操作会返回缓存结果，避免
重复副作用。

ADK `0.6.0` 的 confirmation processor 只从 `LlmAgent.tools` 查找原工具，无法从
`toolsets` 恢复。因此模块仍实现 `AdkMcpToolset : Toolset`，但创建 Agent 时把当轮动态
工具快照 materialize 到 `tools`；工具启停后重建 Runner。这是针对当前 SDK 的兼容层。

## 使用与验证

1. 从首页进入 **ADK Mobile Agent**。
2. 输入临时 Google AI API key；它只保存在进程内存。
3. 选择 QuickJS 统计、添加日程或 90 秒计时示例。
4. 在能力目录和事件轨迹中观察 ADK → MCP → endpoint 调用。

```bash
./gradlew :feat-adk:testDebugUnitTest
./gradlew :feat-adk:connectedDebugAndroidTest   # arm64 真机
./gradlew :app:assembleDebug
```

JDK 11 本地单测覆盖不加载 ADK 字节码的 Android endpoint 行为。ADK `0.6.0` AAR 的
class file target 是 Java 17；Android 构建会由 D8 转换，但 JDK 11 的 JVM 测试进程无法
直接加载，因此脚本模型工具闭环、原生确认恢复、schema 转换和事件格式测试放在
instrumentation 测试中。真机测试同时覆盖 QuickJS 成功、异常、超时、取消、大小限制和
禁用 Android bridge。

## 安全边界

这是 SDK 成色验证，不是生产鉴权方案。移动端直连模型会暴露 API key；生产环境应使用
服务端凭据和受控的远程 Agent/MCP 权限边界。日历权限只在用户允许具体调用后申请。

ADK 的 Android `core` 会传递可选 ML Kit `genai-prompt` 和 Room session backend。本模块
只使用云端 Gemini 与 `InMemorySessionService`，因此排除这两个未使用的 backend；这也
避免 Room 2.8 将宿主工程强制升级到 AGP 8.1.1。模块还将 OpenTelemetry 限定为稳定 API
兼容的 `1.32.0`，并排除 ADK 依赖链中的新版 Error Prone 注解包，以绕开 AGP 7.4 自带
D8 对这些较新 jar 元数据的崩溃；这些兼容约束不影响其他 feature 的工具链。
