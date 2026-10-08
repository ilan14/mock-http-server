# mock-http-server

一个独立的 AI 接口模拟服务，用于 ai-gateway 联调、故障验证和监控学习，无需调用真实模型。

## 功能

- 提供 OpenAI 风格的聊天接口，支持普通响应和流式响应。
- 模拟正常、慢响应、错误、挂起、流中错误及流中停顿六种场景。
- 通过管理接口查询和更新场景配置。
- 提供 JVM 监控指标和 Prometheus 抓取端点。

## 启动

需要 JDK 25 和 Maven。在项目目录执行：

```bash
bash start.sh
```

脚本跳过测试打包并后台启动服务，默认端口为 `8081`。启动输出见 `logs/start.log`，PID 记录在 `logs/app.pid`。

## 使用说明

- [场景配置与调用示例](docs/mock-scenarios.md)
- [监控端点与 Prometheus 配置](docs/monitoring.md)
