# API Gateway - Containerización con Variables Parametrizadas

Este proyecto implementa un API Gateway Spring Boot con configuración completamente parametrizada para diferentes entornos (desarrollo, staging, producción).

## 🚀 Características Principales

- ✅ **Multi-entorno**: Configuración específica para dev, staging y producción
- ✅ **Variables Parametrizadas**: Todas las configuraciones usan variables de entorno
- ✅ **Secret Management**: Gestión segura de credenciales y secrets
- ✅ **Health Checks**: Monitoreo de salud automático
- ✅ **Monitoring Stack**: Integración con Prometheus, Grafana, Loki y Tempo
- ✅ **Zero Downtime**: Despliegue sin tiempo de inactividad en producción
- ✅ **Circuit Breaker**: Resilience4J para tolerancia a fallos
- ✅ **Tracing**: Distribuido con Zipkin/Tempo
- ✅ **Automatización**: Scripts de despliegue por entorno

## 📁 Estructura del Proyecto

```
api-gateway/
├── src/main/resources/
│   ├── application.properties          # Configuración base parametrizada
│   ├── application-dev.properties      # Configuración desarrollo
│   ├── application-staging.properties  # Configuración staging
│   └── application-prod.properties     # Configuración producción
├── scripts/
│   ├── deploy-dev.sh                    # Despliegue desarrollo
│   ├── deploy-staging.sh                # Despliegue staging
│   ├── deploy-prod.sh                   # Despliegue producción
│   └── utils.sh                         # Utilidades varias
├── docker/
│   ├── grafana/                         # Configuración Grafana
│   ├── prometheus/                      # Configuración Prometheus
│   └── tempo/                           # Configuración Tempo
├── .env                                 # Variables de entorno base
├── .env.dev                             # Variables desarrollo
├── .env.staging                         # Variables staging
├── .env.prod                            # Variables producción
├── .env.secrets.template               # Template de secrets
├── docker-compose.yml                   # Compose general
├── docker-compose.prod.yml              # Compose producción
└── docker-compose.override.yml          # Override desarrollo
```

## 🛠️ Configuración de Variables

### Variables Principales

```bash
# Configuración del Servidor
SERVER_PORT=9000                         # Puerto del API Gateway
MANAGEMENT_PORT=9000                      # Puerto de Actuator
SPRING_PROFILES_ACTIVE=dev               # Perfil activo

# Autenticación
KEYCLOAK_ISSUER_URI=http://localhost:8181/realms/life-control-realm

# Circuit Breaker
CIRCUIT_BREAKER_FAILURE_THRESHOLD=50     # Umbral de fallos
TIMEOUT_DURATION=3s                      # Timeout de peticiones
RETRY_MAX_ATTEMPTS=3                     # Intentos de reintento

# Logging & Monitoring
LOG_LEVEL=INFO                           # Nivel de log
TRACING_ENABLED=true                     # Tracing activado
PROMETHEUS_ENABLED=true                  # Prometheus activado

# Health Checks
HEALTH_CHECK_INTERVAL=30s                # Intervalo de checks
HEALTH_CHECK_TIMEOUT=3s                  # Timeout de checks
```

## 🚦 Despliegue por Entorno

### Desarrollo

```bash
# Configurar variables de desarrollo
cp .env.dev .env.local
# Editar .env.local si es necesario

# Desplegar entorno de desarrollo
./scripts/deploy-dev.sh

# Ver logs
./scripts/utils.sh logs

# Verificar salud
./scripts/utils.sh health
```

**Características de Desarrollo:**

- Logs verbosos (DEBUG)
- Health checks rápidos
- Hot reload de configuración
- Tracing con alta frecuencia (50%)
- Todos los endpoints de Actuator expuestos

### Staging

```bash
# Configurar variables de staging
cp .env.staging .env.local
# Editar .env.local si es necesario

# Desplegar entorno de staging
./scripts/deploy-staging.sh
```

**Características de Staging:**

- Logs informativos (INFO)
- Health checks balanceados
- Tracing con frecuencia moderada (5%)
- Endpoints limitados de Actuator
- Tests de integración automáticos

### Producción

```bash
# 1. Configurar secrets
cp .env.secrets.template .env.secrets
# EDITAR .env.secrets CON VALORES REALES

# 2. Configurar variables de producción
cp .env.prod .env.local
# Editar .env.local si es necesario

# 3. Ejecutar como root
sudo ./scripts/deploy-prod.sh
```

**Características de Producción:**

- Logs mínimos (WARN)
- Health checks robustos
- Tracing con baja frecuencia (1%)
- Endpoints seguros de Actuator
- Backup automático antes de despliegue
- Zero downtime deployment
- Resource limits y monitoring

## 🔐 Gestión de Secrets

### Configuración de Secrets

1. **Copiar template**:

   ```bash
   cp .env.secrets.template .env.secrets
   ```

2. **Editar con valores reales**:

   ```bash
   # Base de datos
   MYSQL_ROOT_PASSWORD=supersecreto123
   MYSQL_PASSWORD=otraseguro456

   # Keycloak
   KEYCLOAK_ADMIN_PASSWORD=adminseguro789

   # Aplicación
   JWT_SECRET_KEY=secretojwt256bits...
   ```

3. **Proteger el archivo**:

   ```bash
   chmod 600 .env.secrets
   chown $USER:$USER .env.secrets
   ```

### Variables Sensibles

Las siguientes variables deben mantenerse fuera del código:

- Contraseñas de base de datos
- Secrets de JWT
- API keys externas
- Credenciales de servicios
- Certificados SSL

## 📊 Monitoring y Logs

### Stack de Monitoring

- **Prometheus**: Métricas en `http://localhost:9090`
- **Grafana**: Dashboards en `http://localhost:3000`
- **Loki**: Logs centralizados en `http://localhost:3100`
- **Tempo**: Tracing distribuido en `http://localhost:3110`

### Métricas Clave

El API Gateway expone métricas en `/actuator/prometheus`:

- Rate de peticiones por endpoint
- Latencia promedio
- Error rate
- Estado del Circuit Breaker
- Health del servicio

### Logs Structure

Los logs siguen el formato:

```
{timestamp} [{traceId},{spanId}] {level} {logger} - {message}
```

## 🔄 Circuit Breaker Configuration

### Configuración por Entorno

```bash
# Desarrollo - Más sensible
CIRCUIT_BREAKER_FAILURE_THRESHOLD=30
CIRCUIT_BREAKER_WINDOW_SIZE=5

# Staging - Moderado
CIRCUIT_BREAKER_FAILURE_THRESHOLD=40
CIRCUIT_BREAKER_WINDOW_SIZE=10

# Producción - Robusto
CIRCUIT_BREAKER_FAILURE_THRESHOLD=50
CIRCUIT_BREAKER_WINDOW_SIZE=20
```

### Health Check del Circuit Breaker

Disponible en: `/actuator/health/circuitBreakers`

## 🧪 Testing

### Tests Unitarios

```bash
./gradlew test
```

### Tests de Integración

```bash
./scripts/utils.sh test
```

### Tests de Estrés

Usar herramientas como:

- **Apache Bench (ab)**
- **JMeter**
- **K6**

## 🛠️ Utilidades

### Scripts Disponibles

```bash
# Ver logs en tiempo real
./scripts/utils.sh logs

# Verificar salud del servicio
./scripts/utils.sh health

# Reiniciar servicio
./scripts/utils.sh restart

# Detener todos los servicios
./scripts/utils.sh stop

# Limpiar recursos Docker
./scripts/utils.sh clean

# Ejecutar tests
./scripts/utils.sh test
```

### Monitoreo Manual

```bash
# Ver estado de contenedores
docker-compose ps

# Ver logs específicos
docker-compose logs -f api-gateway

# Entrar al contenedor
docker-compose exec api-gateway sh

# Ver métricas
curl http://localhost:9000/actuator/prometheus
```

## 🔧 Troubleshooting

### Problemas Comunes

1. **Servicio no inicia**:

   ```bash
   docker-compose logs api-gateway
   ```

2. **Health check falla**:

   ```bash
   curl http://localhost:9000/actuator/health
   ```

3. **Variables no cargadas**:

   ```bash
   docker-compose config
   ```

4. **Permisos denegados**:

   ```bash
   chmod +x scripts/*.sh
   ```

### Debug Mode

Activar debug logging temporalmente:

```bash
export LOG_LEVEL=DEBUG
docker-compose up api-gateway
```

## 📝 Mejores Prácticas

### Desarrollo

- Usar `.env.local` para variables personales
- No commit de secrets
- Usar hot reload para desarrollo rápido
- Activar tracing completo para debugging

### Producción

- Siempre hacer backup antes de despliegue
- Usar zero downtime deployment
- Limitar endpoints de Actuator
- Monitorizar recursos y métricas
- Usar rotation de logs
- Configurar alerting

### Seguridad

- Nunca commitear `.env.secrets`
- Usar contraseñas fuertes
- Limitar exposición de puertos
- Usar HTTPS en producción
- Regularmente rotar secrets

## 🤝 Contribución

1. Hacer fork del proyecto
2. Crear feature branch
3. Implementar cambios con tests
4. Verificar que pasen todos los checks
5. Hacer Pull Request

## 📄 Licencia

Este proyecto está bajo licencia MIT.

