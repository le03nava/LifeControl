# ⚠️ Order Service — DEPRECATED

> **Este servicio está deprecado.** Su funcionalidad se migrará a `life-control-api` y el módulo será eliminado en el futuro.
>
> **Alternativa**: Toda la lógica de órdenes pasará a `life-control-api/`.

---

## Estado Actual

| Aspecto         | Detalle                                   |
|-----------------|-------------------------------------------|
| Framework       | Spring Boot 3.4.0                        |
| Lenguaje        | Java 21                                   |
| Build           | Gradle                                    |
| Base de datos   | MySQL                                     |
| Migraciones     | Flyway                                    |
| Mensajería      | Kafka (publica `order-placed` con Avro)   |
| Circuit Breaker | Resilience4j (Spring Cloud)               |
| Package         | `com.lifecontrol.order`                   |
| Lombok          | Sí (`@RequiredArgsConstructor`)           |

## Endpoints

| Método | Path           | Descripción                        |
|--------|----------------|------------------------------------|
| POST   | `/api/order`   | Crear una orden (publica evento Kafka) |

## Recursos a migrar

- `controller/OrderController.java`
- `service/OrderService.java`
- `model/Order.java`
- `repository/OrderRepository.java`
- `dto/OrderRequest.java`
- `event/OrderPlacedEvent.java`
- `client/InventoryClient.java`
- `config/OpenAPIConfig.java`, `ObservationConfig.java`, `RestClientConfig.java`

## Notas de migración

- Kafka + Avro debe coexistir con el resto del stack en `life-control-api`
- `OrderPlacedEvent` es compartido con `notification-service` — considerar reubicación
- El `InventoryClient` (REST client) será reemplazable por llamada directa en el monolito
- MySQL → migrar a PostgreSQL de `life-control-api`
- Lombok debe reemplazarse por constructor explícito
- Resilience4j puede no ser necesario si no hay llamadas externas
