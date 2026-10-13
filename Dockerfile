# ==========================================
# Multi-stage Dockerfile for HAR to JMeter Web App
# ==========================================

# 1. Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Cache Maven dependencies by copying pom.xml first
COPY webapp/pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy source code and build executable jar
COPY webapp/src ./src
RUN mvn clean package -DskipTests

# 2. Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user and prepare uploads directory
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /tmp/har-convertor-web-uploads && \
    chown -R appuser:appgroup /tmp/har-convertor-web-uploads

# Copy packaged jar from builder
COPY --from=builder --chown=appuser:appgroup /app/target/har-convertor-webapp-*.jar app.jar

USER appuser:appgroup

ENV PORT=8080 \
    FORWARD_HEADERS_STRATEGY=none \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:${PORT}/ || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
