# ⚠️ Product Service — DEPRECATED

> **Este servicio está deprecado.** Su funcionalidad se migrará a `life-control-api` y el módulo será eliminado en el futuro.
>
> **Alternativa**: Toda la lógica de productos pasará a `life-control-api/`.

---

## Estado Actual

| Aspecto       | Detalle                           |
|---------------|-----------------------------------|
| Framework     | Spring Boot 3.4.0                |
| Lenguaje      | Java 21                           |
| Build         | Gradle                            |
| Base de datos | PostgreSQL                        |
| Package       | `com.lifecontrol.product`         |
| Lombok        | Sí (`@RequiredArgsConstructor`)   |

## Endpoints

| Método | Path                     | Descripción                               |
|--------|--------------------------|-------------------------------------------|
| POST   | `/api/products`          | Crear producto                            |
| GET    | `/api/products`          | Listar productos (paginado, con búsqueda) |
| GET    | `/api/products/{id}`     | Obtener producto por UUID                 |
| PUT    | `/api/products/{id}`     | Actualizar producto                       |
| DELETE | `/api/products/{id}`     | Soft-delete producto                      |

## Recursos a migrar

- `controller/ProductController.java`
- `service/ProductService.java`
- `model/Product.java`
- `repository/ProductRepository.java`
- `dto/ProductCreateRequest.java`, `ProductUpdateRequest.java`, `ProductResponse.java`
- `exception/ProductNotFoundException.java`, `GlobalExceptionHandler.java`
- `config/OpenAPIConfig.java`

## Notas de migración

- Coincide con PostgreSQL de `life-control-api` — migración de esquema directa
- La paginación con búsqueda ya existe en `life-control-api` (mismo patrón que `CompanyService`)
- Lombok debe reemplazarse por constructor explícito
- El `GlobalExceptionHandler` debe fusionarse con el de `life-control-api`
- Revisar solapamiento de endpoints: productos podría ser un sub-recurso de compañía
