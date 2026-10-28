# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# 依赖层与源码分离，源码变更时继续复用 Maven 仓库缓存。
COPY pom.xml ./
RUN --mount=type=cache,id=mock-http-server-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,id=mock-http-server-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir /app/logs && chown 10001:10001 /app/logs
COPY --from=build --chown=10001:10001 /build/target/mock-http-server-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
