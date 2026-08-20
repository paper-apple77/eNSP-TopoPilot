# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先拷贝 pom 利用 Docker 层缓存，依赖没变就不用重新下载
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ===== 运行阶段：非 root 用户提升安全性 =====
FROM eclipse-temurin:17-jre
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app
EXPOSE 8080
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
