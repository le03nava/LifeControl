# ============================================
# API Gateway - Dockerfile
# ============================================
# Build the JAR locally FIRST:
#   cd api-gateway && ./gradlew bootJar -Pprofile=dev
# Then build the Docker image:
#   docker build -t api-gateway:latest .
# ============================================

# ETAPA ÚNICA: Ejecución (RUN)
# El JAR se buildea LOCALMENTE para evitar depender de internet
# dentro del contenedor Docker (problemas comunes de DNS/proxy)
#
# La imagen base no se escribe acá. `JAVA_BASE_IMAGE` es una key de `docker/.env.template`, el compose
# la pasa como build arg y `docker/scripts/tests/image-pins.test.sh` falla si esta línea, la key y el
# default del compose divergen. Un Dockerfile que nombra su propia imagen base es un pin que ningún
# chequeo mira: así `eclipse-temurin:21-jre-alpine` podía cambiar de generación sin un commit acá.
# El default del ARG mantiene `docker build .` funcionando fuera de compose, con el mismo valor.
ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${JAVA_BASE_IMAGE}

ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG APP_VERSION=0.0.1-SNAPSHOT
ARG JAR_FILE=build/libs/api-gateway-${APP_VERSION}.jar
# Revisión git inyectada en build-time para trazabilidad de la imagen
ARG GIT_COMMIT=unknown

ENV SERVER_PORT=${SERVER_PORT:-9000}
ENV MANAGEMENT_PORT=${MANAGEMENT_PORT:-9000}
# No SPRING_PROFILES_ACTIVE default, for the same reason as life-control-api/Dockerfile: no image
# bakes a `dev` profile. The gateway has no Logbook profile guard of its own, so this removal is
# inert here today (compose declares the variable explicitly) and it is what keeps the two images
# consistent; the gateway's own `dev` default lives in its application.properties and is not this
# unit's subject.
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

# El HEALTHCHECK de este archivo sondea con `wget`, que la imagen base ya trae: el `apk add curl`
# que vivía acá no lo usaba nadie, y su `|| true` escondía un install fallido. Una dependencia
# necesaria se instala sin red de seguridad; si el build no puede resolverla, el build falla.

USER ${APP_USER}

WORKDIR /app

LABEL maintainer="LifeControl Team" \
      version="${APP_VERSION}" \
      description="API Gateway with multi-environment support" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

HEALTHCHECK --interval=30s \
            --timeout=3s \
            --start-period=60s \
            --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT:-9000}/actuator/health || exit 1

# exec para que java sea PID 1 y reciba SIGTERM (graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
