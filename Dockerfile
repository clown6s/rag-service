# RAG 服务 Dockerfile
FROM eclipse-temurin:21-jre-alpine

# 创建非 root 用户 + 安装 wget（P1-8: alpine 默认无 wget，HEALTHCHECK 需要）
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget

WORKDIR /app

COPY target/*.jar app.jar

# 非 root 运行（容器内降权，防 RCE 逃逸）
USER app

EXPOSE 8080

# 健康检查：检测 8080 端口 HTTP 响应（服务存活即返回 HTTP，含 404）
# 引入 spring-boot-starter-actuator 后可改为 /actuator/health
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=40s \
  CMD wget -q --spider http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
