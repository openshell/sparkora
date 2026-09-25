# =============================================================================
#  Sparkora 前端镜像（多阶段构建）
#
#  build 阶段：Node 22 构建 Vite 静态产物
#  runtime 阶段：nginx 托管 dist + 反代 /api 到 backend 容器
#
#  构建：docker compose build frontend
# =============================================================================

# ---------- 阶段 1：构建（Node 22）----------
FROM node:22-alpine AS build
WORKDIR /build

# 先复制依赖清单，利用层缓存（package-lock.json 未变则不重复 npm ci）
COPY package.json package-lock.json ./
RUN npm ci

# 复制源码构建
COPY . .
RUN npm run build


# ---------- 阶段 2：运行（nginx 静态托管 + 反代）----------
FROM nginx:1.27-alpine AS runtime

# 站点配置为 envsubst 模板（含 ${SERVER_PORT} 占位符），由 nginx 官方镜像的
# /docker-entrypoint.d/20-envsubst-on-templates.sh 在容器启动时渲染到 conf.d/default.conf。
# 需显式声明 envsubst 的变量白名单：该脚本默认只替换已定义的变量，
# 未列出的变量（如 nginx 运行时变量 $host/$remote_addr）会被原样保留，故安全。
# compose 已把 SERVER_PORT 注入本容器 environment。
ENV SERVER_PORT=5661 \
    NGINX_ENVSUBST_TEMPLATE_SUFFIX=.template \
    NGINX_ENVSUBST_OUTPUT_DIR=/etc/nginx/conf.d \
    NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# 拷贝构建产物
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
