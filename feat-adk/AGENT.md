# AGENT.md

## 模块角色

`feat-adk` 用于评估 Google ADK Kotlin 在 Android 端的集成质量，重点是 Agent
编排、工具调用、会话和事件流，不承担生产聊天业务。

## 约束

- 保持 API key 仅在内存中，不写入资源、`BuildConfig`、日志、Bundle 或偏好设置
- 工具统一通过 `McpEndpoint` 和 `AgentCapabilityRegistry` 接入；不要把特定工具行为写进
  通用 Agent 指令
- 纯计算工具可自动执行；Android 系统副作用必须逐次使用 ADK confirmation
- QuickJS Agent endpoint 必须保留 `android.invoke` 全拒绝策略与资源上限
- 每轮 `RunConfig.maxLlmCalls` 必须保持有限值，避免异常工具循环
- 升级 ADK 时同步核对 Kotlin 元数据版本、AGP/R8 要求和 Android `minSdk`
- 新增事件展示时不要输出认证信息或完整请求头

## 修改建议

- 优先补充可观察性与离线单元测试
- 若接入 Firebase AI、ML Kit Gemini Nano、Room Session 或 KSP `@Tool`，拆分清晰的
  验证入口或服务类，不要把初始化逻辑继续堆进 Activity
- 生产化尝试应另建模块，并使用服务端凭据与权限边界
