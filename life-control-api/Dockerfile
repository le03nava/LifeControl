# ============================================
# LifeControl API - Dockerfile Multi-Entorno
# ============================================

# ETAPA 1: Construcción (BUILDER)
FROM gradle:8.5-jdk21-alpine AS builder

ARG BUILD_PROFILE=dev
ARG APP_VERSION=0.0.1-SNAPSHOT

WORKDIR /app

COPY build.gradle settings.gradle ./

COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

RUN gradle bootJar -Pprofile=${BUILD_PROFILE} --no-daemon

# ------------------------------------------------------------------------------------------

# ETAPA 2: Ejecución (RUN)
FROM eclipse-temurin:21-jre-alpine

ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG JAR_FILE=build/libs/lifecontrol-api-0.0.1-SNAPSHOT.jar

ENV SERVER_PORT=${SERVER_PORT:-8082}
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

EXPOSE ${SERVER_PORT}

COPY --from=builder /app/${JAR_FILE} /app/app.jar

RUN apk add --no-cache curl && \
    addgroup -g 1001 ${APP_GROUP} && \
    adduser -u 1001 -G ${APP_GROUP} -s /bin/sh -D ${APP_USER}

USER ${APP_USER}

WORKDIR /app

LABEL maintainer="LifeControl Team" \
      version="${APP_VERSION}" \
      description="LifeControl API with multi-environment support"

HEALTHCHECK --interval=30s \
            --timeout=3s \
            --start-period=60s \
            --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT:-8082}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
