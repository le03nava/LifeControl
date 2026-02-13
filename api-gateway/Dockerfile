# ============================================
# API Gateway - Dockerfile Multi-Entorno
# ============================================

# ETAPA 1: Construcción (BUILDER)
# Usamos una imagen que incluye un JDK (para compilar) y Gradle
FROM gradle:8.5-jdk21-alpine AS builder

# Argumentos de build para configurar compilación
ARG BUILD_PROFILE=dev
ARG APP_VERSION=0.0.1-SNAPSHOT

# Establece el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copia los archivos de configuración de Gradle
# Orden optimizado para aprovechar caché de Docker
COPY build.gradle settings.gradle ./

# Copia solo lo necesario del código fuente
COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

# Ejecuta la construcción usando Gradle con perfil específico
# Se usa 'build --no-daemon' para evitar problemas con la caché de Gradle en contenedores.
RUN gradle bootJar -Pprofile=${BUILD_PROFILE} --no-daemon

# ------------------------------------------------------------------------------------------

# ETAPA 2: Ejecución (RUN)
# Utilizamos una imagen JRE ligera y segura (Alpine) para el entorno de ejecución
FROM eclipse-temurin:21-jre-alpine

# Argumentos de configuración
ARG APP_USER=appuser
ARG APP_GROUP=appgroup
ARG JAR_FILE=build/libs/api-gateway-0.0.1-SNAPSHOT.jar

# Variables de entorno con valores por defecto
ENV SERVER_PORT=${SERVER_PORT:-9000}
ENV MANAGEMENT_PORT=${MANAGEMENT_PORT:-9000}
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

# Puerto configurable dinámicamente
EXPOSE ${SERVER_PORT}

# Copia el JAR generado de la etapa de construcción a la etapa de ejecución
COPY --from=builder /app/${JAR_FILE} /app/app.jar

# Instala dependencias necesarias para health checks
RUN apk add --no-cache curl && \
    addgroup -g 1001 ${APP_GROUP} && \
    adduser -u 1001 -G ${APP_GROUP} -s /bin/sh -D ${APP_USER}

# Cambia a usuario no root para seguridad
USER ${APP_USER}

WORKDIR /app

# Labels para metadata
LABEL maintainer="LifeControl Team" \
      version="${APP_VERSION}" \
      description="API Gateway with multi-environment support"

# Health check configurable para monitoreo
# Nota: Los valores no pueden usar variables de entorno directamente en HEALTHCHECK
HEALTHCHECK --interval=30s \
            --timeout=3s \
            --start-period=60s \
            --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT:-9000}/actuator/health || exit 1

# Comando para ejecutar la aplicación Spring Boot con Java Options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
