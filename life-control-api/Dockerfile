# ============================================
# LifeControl API - Dockerfile Multi-Entorno
# ============================================
# Build the JAR locally FIRST:
#   cd life-control-api && ./gradlew bootJar -Pprofile=dev
# Then build the Docker image:
#   docker build -t life-control-api:latest .
# ============================================

# ETAPA ÚNICA: Ejecución (RUN)
# El JAR se buildea LOCALMENTE para evitar depender de internet
# dentro del contenedor Docker (problemas comunes de DNS/proxy)
FROM eclipse-temurin:21-jre-alpine

ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG APP_VERSION=0.0.1-SNAPSHOT
ARG JAR_FILE=build/libs/life-control-api-${APP_VERSION}.jar

ENV SERVER_PORT=${SERVER_PORT:-8082}
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

EXPOSE ${SERVER_PORT}

# Copiar JAR pre-buildeado localmente
COPY ${JAR_FILE} /app/app.jar

# Verificar que el JAR existe y no está vacío
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
