# =============================================================================
#  Sparkora 后端镜像（多阶段构建）
#
#  为什么运行镜像要内置 Node：后端预览链路（PreviewService）直接 ProcessBuilder 调用
#  `wenyan` CLI 做同核渲染，因此运行镜像不能只装 JRE，必须带 Node + @wenyan-md/cli。
#
#  构建：docker compose build backend（或 docker build -t sparkora-backend:local .）
#  注意：.env 由 compose 的 env_file 在运行时注入，**绝不** COPY 进镜像。
# =============================================================================

# ---------- 阶段 1：构建（Maven + JDK 21）----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只复制 pom.xml 解析依赖，利用 Docker 层缓存（pom.xml 未变则不重复下载）
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# 再复制源码打包（.dockerignore 已排除 target/，不会把宿主构建产物带进上下文）
COPY src ./src
RUN mvn -q -B -DskipTests package


# ---------- 阶段 2：运行（JRE 21 + Node + wenyan CLI）----------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Node 22 + curl。
# 说明：Ubuntu jammy 自带源里的 nodejs 版本过旧（12.x），跑不动 @wenyan-md/cli 2.x，
#      故经 NodeSource 安装 Node 22；curl 供 healthcheck 探活使用。
# 版本对齐：@wenyan-md/cli@2.0.11 与远程 wenyan-server 同核（见 WenyanServerService 注释实测）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @wenyan-md/cli@2.0.11 \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# jar 内含 db/schema.sql（spring.sql.init.mode=always 启动时幂等执行建表）
COPY --from=build /build/target/*.jar /app/app.jar

# WENYAN_CLI_PATH=wenyan：全局安装后位于 PATH（/usr/bin/wenyan），与 application.yml 默认值一致
# IMAGE_STORAGE_DIR=/app/data/images：仅 wenyan 渲染临时文件落位；
#   实际目录 {storage}/../tmp/preview 与 data/tmp/wenyan-themes 由 compose 挂卷持久化
ENV WENYAN_CLI_PATH=wenyan \
    IMAGE_STORAGE_DIR=/app/data/images

# 文档性声明：实际监听端口由 .env 的 SERVER_PORT 决定
EXPOSE 5661

# exec 形式：保证信号正确转发、容器可优雅停止
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
