# Mock 场景需求与技术方案

## 需求

服务独立部署，ai-gateway 通过 OpenAI 风格 `POST /v1/chat/completions` 调用。
保持 Spring MVC、Servlet 异步请求和内嵌 Tomcat，不引入 WebFlux、数据库或新依赖。
通过全局 Settings 决定模拟行为，不读取场景请求头。

- `GET /mock/settings` 查询配置。
- `PUT /mock/settings` 完整替换配置，返回新配置；所有字段必须提供，非法配置返回 400 且不覆盖旧配置。
- 配置仅保存在内存，重启恢复 NORMAL 默认值。
- 请求进入 Service 时读取不可变快照，运行中不受配置更新影响。

## 配置语义

| 字段 | 含义与范围 |
|---|---|
| scenario | NORMAL、SLOW、ERROR、HANG、STREAM_ERROR、STREAM_STALL |
| delayMs | 所有场景开始前的基础等待，0..60000 毫秒 |
| jitterMs | 每个请求额外随机等待 [0, jitterMs]，0..60000 毫秒 |
| frameIntervalMs | 内容帧间隔，首帧不额外等待，0..10000 毫秒 |
| frames | 正常内容帧数，1..1000，不含角色、结束和 usage 帧 |
| faultAfterFrames | 故障前发送内容帧数，0..frames |
| errorStatus | 429、500、503 |
| failureRate | ERROR 的失败概率，有限数值 0..1；不影响其他场景 |
| content | 普通回复及每个内容帧的完整文本，最多 4096 个 Java 字符，允许空字符串 |

默认值：NORMAL、delayMs=0、jitterMs=0、frameIntervalMs=20、frames=5、faultAfterFrames=2、errorStatus=503、failureRate=1、content="ok"。

NORMAL/SLOW 均等待后成功；SLOW 不额外叠加固定延迟。
ERROR 等待后按概率返回指定 HTTP 状态和 OpenAI 风格 JSON 错误，未命中则成功。
HANG 等待后不产生响应。STREAM_ERROR/STREAM_STALL 在普通请求中按成功处理。
流式成功逐帧重复 content，最后发送 stop、可选 usage 和 [DONE]；usage 固定为 0。
STREAM_ERROR 输出指定内容帧数后发送 SSE data 错误对象并完成，不发送 stop、usage 或 [DONE]。
STREAM_STALL 输出指定内容帧数后保持连接，不发送任何结束标记。
角色帧只在准备发送第一个内容帧时发送；faultAfterFrames=0 时不先发送角色帧。
流已经提交 HTTP 200 后，错误对象中的 code 携带 errorStatus，不能改变 HTTP 状态。
本方案不模拟 TCP reset，也不保证 gateway 会自动将流内错误对象识别为重试信号。

## 实现

- Scenario / Settings：不可变模型与 Bean Validation、跨字段校验。
- MockSettingsService：volatile 保存 Settings；校验完整对象成功后原子替换。
- MockSettingsController：轻量查询与更新入口。
- ChatService：统一返回 DeferredResult<ResponseEntity<?>>，延迟阶段不提交响应头。
  普通成功/HTTP 错误返回 JSON；流式成功分支才创建 SseEmitter。
- ThreadPoolTaskScheduler：只负责触发任务；应用 TaskExecutor 执行响应生成与可能阻塞的 SSE 写入。
  每请求串行调度下一帧，不使用 sleep，不提前排队全部帧；帧间隔在前一帧写入完成后计算。
- 每请求保存关闭状态与待执行 Future，结束、错误、超时取消后续调度。
  已开始的阻塞写入无法靠取消调度强制终止。
- mock.stream-timeout-ms 作为请求总生命周期上限，默认 300000 毫秒，包含初始等待。
  超时前尚未输出时返回 504 JSON；已输出时完成响应，不发送成功结束标记。
  gateway 超时应短于此上限。HANG 不写数据，客户端断开可能无法立即被容器感知，最终由上限回收。
- 调度/发送执行器饱和：开始响应前返回 503；已开始流时终止，不伪造成功标记。

## 验证

单元测试覆盖 Settings 边界与原子更新；真实 Tomcat 集成测试覆盖管理接口、普通/SSE 成功、
延迟及快照隔离、ERROR 概率边界和状态码、HANG/STALL 超时、流中错误与 0 帧故障。
README 不修改，不自动提交。

## 调用示例

```bash
curl -X PUT http://localhost:8081/mock/settings \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"SLOW","delayMs":3000,"jitterMs":500,"frameIntervalMs":200,"frames":5,"faultAfterFrames":2,"errorStatus":503,"failureRate":1,"content":"ok"}'
curl -N http://localhost:8081/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"mock","messages":[{"role":"user","content":"hi"}],"stream":true}'
```
