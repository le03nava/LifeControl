# ⚠️ Inventory Service — DEPRECATED

> **Este servicio está deprecado.** Su funcionalidad se migrará a `life-control-api` y el módulo será eliminado en el futuro.
>
> **Alternativa**: Toda la lógica de inventario pasará a `life-control-api/`.

---

## Estado Actual

| Aspecto       | Detalle                           |
|---------------|-----------------------------------|
| Framework     | Spring Boot 3.4.0                |
| Lenguaje      | Java 21                           |
| Build         | Gradle                            |
| Base de datos | MySQL                             |
| Migraciones   | Flyway                            |
| Package       | `com.lifecontrol.inventory`       |
| Lombok        | Sí (`@RequiredArgsConstructor`)   |

## Endpoints

| Método | Path                        | Descripción                        |
|--------|-----------------------------|------------------------------------|
| GET    | `/api/inventory`            | Verifica si un producto está en stock (`?skuCode=&quantity=`) |

## Recursos a migrar

- `controller/InventoryController.java`
- `service/InventoryService.java`
- `model/Inventory.java`
- `repository/InventoryRepository.java`

## Notas de migración

- El endpoint pasa a `life-control-api` bajo el dominio de inventario/stock
- La lógica es actualmente muy simple (consulta booleana)
- Se recomienda unificar con PostgreSQL (no MySQL) en `life-control-api`
- Lombok debe reemplazarse por constructores explícitos y records (ver convenciones de `life-control-api/AGENTS.md`)
