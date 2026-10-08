# JVM 监控与 Prometheus

## 实现

引入 spring-boot-starter-actuator 和 micrometer-registry-prometheus，版本由 Spring Boot 管理。
使用内置 JVM 指标绑定，不添加业务埋点或新的管理服务。
不设置 management.server.port，Actuator 与业务接口共用主端口，当前默认 8081。
所有指标包含 application="mock-http-server" 标签。

## 端点

- GET /actuator/health：服务健康状态。
- GET /actuator/metrics：Micrometer 指标名称列表。
- GET /actuator/metrics/jvm.memory.used：查看单个 JVM 指标的测量值和可用标签。
- GET /actuator/prometheus：Prometheus 文本格式抓取端点。

JVM 指标包括堆/非堆内存、GC、线程、类加载、缓冲池等。
GC pause 等事件型指标可能在实际发生 GC 后才出现；具体指标依赖 JVM 和垃圾收集器。
同时自动提供进程、CPU、HTTP 等框架内置指标。

## Prometheus 抓取配置示例

将以下 job 合并到 Prometheus 的 scrape_configs；服务本身只导出指标，不启动 Prometheus 服务。

```yaml
scrape_configs:
  - job_name: mock-http-server
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8081']
```

localhost 仅适用于 Prometheus 与应用运行在同一网络主机；容器或远程部署时改成应用可达的地址。

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/metrics/jvm.memory.used
curl http://localhost:8081/actuator/prometheus
```

## 验证

真实 Tomcat 集成测试从应用主端口验证 health、JVM metrics 和 Prometheus 格式及 application 标签。

参考：[Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)。
