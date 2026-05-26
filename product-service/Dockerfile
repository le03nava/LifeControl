# ============================================
# Product Service - Dockerfile
# ============================================
# Build the JAR locally FIRST:
#   cd product-service && ./gradlew bootJar -Pprofile=dev
# Then build the Docker image:
#   docker build -t product-service:latest .
# ============================================

# ETAPA ÚNICA: Ejecución (RUN)
# El JAR se buildea LOCALMENTE para evitar depender de internet
# dentro del contenedor Docker (problemas comunes de DNS/proxy)
FROM eclipse-temurin:21-jre-alpine

ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG APP_VERSION=0.0.1-SNAPSHOT
ARG JAR_FILE=build/libs/product-service-${APP_VERSION}.jar

ENV SERVER_PORT=${SERVER_PORT:-8080}
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

# Crear usuario/grupo sin depender de apk
RUN addgroup -g 1001 ${APP_GROUP} && \
    adduser -u 1001 -G ${APP_GROUP} -s /bin/sh -D ${APP_USER}

# Intentar instalar curl para HEALTHCHECK; si falla DNS no bloquea el build
RUN apk add --no-cache curl || true

USER ${APP_USER}

WORKDIR /app

LABEL maintainer="LifeControl Team" \
      version="${APP_VERSION}" \
      description="Product Service with multi-environment support"

# HEALTHCHECK: usa curl si está instalado, sino wget (busybox), sino falla
HEALTHCHECK --interval=30s \
            --timeout=3s \
            --start-period=60s \
            --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || \
      wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
