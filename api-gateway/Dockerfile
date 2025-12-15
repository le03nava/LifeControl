# ETAPA 1: Construcción (BUILDER)
# Usamos una imagen que incluye un JDK (para compilar) y Gradle
FROM gradle:8.5-jdk17-alpine AS builder

# Establece el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copia los archivos de configuración de Gradle y el wrapper (para descargar dependencias)
COPY build.gradle settings.gradle ./
COPY gradlew gradlew.bat ./
COPY gradle ./gradle

# Copia el código fuente (incluyendo src)
COPY src ./src

# Opcional: Copia los archivos de configuración de la app
# COPY src/main/resources ./src/main/resources

# Ejecuta la construcción. 
# Usamos 'bootJar' (si está configurado) para obtener el JAR ejecutable de Spring Boot.
# Se usa 'build --no-daemon' para evitar problemas con la caché de Gradle en contenedores.
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# ------------------------------------------------------------------------------------------

# ETAPA 2: Ejecución (RUN)
# Utilizamos una imagen JRE ligera y segura (Alpine) para el entorno de ejecución
FROM eclipse-temurin:17-jre-alpine

# Define el puerto que expone la aplicación Spring Boot
EXPOSE 8080

# Define un argumento para el nombre del archivo JAR generado por Spring Boot
# Por defecto, el JAR se llama 'app.jar' si se usa 'bootJar'
ARG JAR_FILE=build/libs/*.jar

# Copia el JAR generado de la etapa de construcción a la etapa de ejecución
# Esto reduce el tamaño final de la imagen al dejar fuera todas las herramientas de construcción
COPY --from=builder /app/${JAR_FILE} app.jar

# Comando para ejecutar la aplicación Spring Boot
ENTRYPOINT ["java", "-jar", "/app.jar"]
