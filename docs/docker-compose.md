# Docker Compose 部署

## 环境要求与启动

安装支持 BuildKit 的 Docker Engine（或 Docker Desktop）及 Docker Compose v2 或更高版本，无需在宿主机安装 Java 和 Maven。

在项目根目录执行：

```bash
docker compose up -d --build
curl --fail http://localhost:8081/actuator/health
```

镜像通过 Maven 和 JDK 25 构建并运行单元测试，仅将可执行 JAR 复制到 Java 25 JRE 运行镜像，以非 root 用户运行。首次构建需要下载基础镜像、依赖及插件，请等待构建完成后检查健康端点。

默认宿主机端口为 8081，可通过环境变量或项目根目录的 `.env` 配置：

```bash
HTTP_PORT=18081 docker compose up -d --build
curl --fail http://localhost:18081/actuator/health
```

`JAVA_TOOL_OPTIONS` 可覆盖默认 JVM 参数 `-Dfile.encoding=UTF-8 -Xms256m -Xmx512m`。容器内部端口固定为 8081。Compose 的 `.env` 用于变量插值，不会将其中所有变量自动注入容器。

## Maven 缓存原理

Dockerfile 先复制 `pom.xml` 并预下载依赖，再复制源码打包。仅修改源码时，依赖预下载层可以直接复用。

两个 Maven 步骤共享 BuildKit 的 `/root/.m2` 缓存；即使修改 `pom.xml` 触发重新构建，已下载的依赖仍可复用，仅补充缺少或更新的依赖。使用 `sharing=locked` 避免并发构建同时写入仓库。

缓存保存在当前 Docker builder 中，独立于容器生命周期，`docker compose down` 不会删除它。首次构建、切换 builder 或清理构建缓存后需要重新下载。`--no-cache` 会重新执行构建步骤，但不会主动清除 Maven 缓存挂载；SNAPSHOT 等动态依赖仍可能访问远程仓库检查更新。

可连续执行以下命令观察第二次输出中的 `CACHED`；修改源码后再次构建，可观察打包步骤复用本地 Maven 仓库：

```bash
docker compose --progress plain build
docker compose --progress plain build
```

## 常用操作

```bash
# 查看状态和日志
docker compose ps
docker compose logs -f --tail=100

# 更新源码后重新构建并部署
docker compose up -d --build

# 停止并移除容器和网络，保留构建缓存
docker compose down
```

容器标准输出日志由 Docker 轮转，单个文件上限 10 MB，最多保留 3 个。应用同时按照现有 Logback 配置写入容器内 `/app/logs`，重建容器会丢失这些文件，可通过 `docker compose logs` 查看标准输出日志。服务配置保存在内存中，重建或重启容器会恢复默认值。现有接口和监控用法见同目录下的场景及监控文档。
