# ⚠️ Notification Service — DEPRECATED

> **Este servicio está deprecado.** Su funcionalidad se migrará a `life-control-api` y el módulo será eliminado en el futuro.
>
> **Alternativa**: Toda la lógica de notificaciones pasará a `life-control-api/`.

---

## Estado Actual

| Aspecto       | Detalle                           |
|---------------|-----------------------------------|
| Framework     | Spring Boot 3.4.0                |
| Lenguaje      | Java 21                           |
| Build         | Gradle                            |
| Mensajería    | Kafka (topic `order-placed`)      |
| Email         | Spring Mail (`JavaMailSender`)    |
| Package       | `com.lifecontrol.notification`    |
| Lombok        | Sí (`@RequiredArgsConstructor`, `@Slf4j`) |

## Endpoints

Ninguno REST. Es un **consumer Kafka** que escucha `order-placed` y envía emails.

## Recursos a migrar

- `service/NotificationService.java` (Kafka listener + email sender)
- `event/OrderPlacedEvent.java` (evento compartido con order-service)
- `config/ObservationConfig.java`

## Notas de migración

- El Kafka listener debe vivir en `life-control-api` bajo un módulo de notificaciones
- `OrderPlacedEvent` está en `com.lifecontrol.order.event` — hay que moverlo o compartirlo
- Lombok debe reemplazarse por constructores explícitos
- Evaluar si la funcionalidad de email es necesaria en el nuevo diseño
