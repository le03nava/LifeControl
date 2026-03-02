# ============================================
# LifeControl API - Dockerfile Multi-Entorno
# ============================================

# ETAPA 1: Construcción (BUILDER)
FROM gradle:8.5-jdk21-alpine AS builder

ARG BUILD_PROFILE=dev
ARG APP_VERSION=0.0.1-SNAPSHOT

WORKDIR /app

# Debug: Show what we're building
RUN echo "Building life-control-api with profile: ${BUILD_PROFILE}"

COPY build.gradle settings.gradle ./

COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

# Debug: Verify files were copied
RUN ls -la

# Build the JAR
RUN gradle bootJar -Pprofile=${BUILD_PROFILE} --no-daemon --stacktrace || (echo "GRADLE BUILD FAILED" && exit 1)

# Debug: Verify JAR was created
RUN ls -la build/libs/ && \
    if [ ! -f "build/libs/life-control-api-${APP_VERSION}.jar" ]; then \
        echo "JAR FILE NOT FOUND!"; \
        ls -la build/libs/; \
        exit 1; \
    fi

# ------------------------------------------------------------------------------------------

# ETAPA 2: Ejecución (RUN)
FROM eclipse-temurin:21-jre-alpine

ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG APP_VERSION=0.0.1-SNAPSHOT
ARG JAR_FILE=build/libs/life-control-api-${APP_VERSION}.jar

ENV SERVER_PORT=${SERVER_PORT:-8082}
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

EXPOSE ${SERVER_PORT}

# Debug: Verify source JAR exists before copying
RUN echo "Looking for JAR: /app/${JAR_FILE}"

COPY --from=builder /app/${JAR_FILE} /app/app.jar

# Verify JAR was copied
RUN ls -la /app/app.jar && \
    if [ ! -s /app/app.jar ]; then \
        echo "JAR FILE IS EMPTY!"; \
        exit 1; \
    fi

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
