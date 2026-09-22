# Life Control API — Developer Guide

## Tech Stack

| Component        | Technology                              |
|------------------|-----------------------------------------|
| Framework        | Spring Boot 3.5.16                     |
| Language         | Java 21                                 |
| Build Tool       | Gradle 8.x+                             |
| Database         | PostgreSQL                              |
| Cache            | Redis (with in-memory fallback)         |
| Port             | 8082 (configurable via `SERVER_PORT`)   |
| Package          | `com.lifecontrol.api`                   |

---

## Architecture

### Layered + Cross-Cutting

```
Controller → Service → Repository   (primary flow)
         ↓
     Aspect → Event → Listener       (audit trail, events)
         ↓
    Config/Filter                     (cross-cutting: security, rate-limit, caching)
```

| Layer               | Responsibility                                       | Annotation                                  |
|---------------------|------------------------------------------------------|---------------------------------------------|
| **Controller**      | Handle HTTP requests, validate input, return responses | `@RestController`, `@RequestMapping`     |
| **Service**         | Business logic, transactions, coordination             | `@Service`, `@Transactional`              |
| **Repository**      | Data access, JPA operations                            | `@Repository` (Spring Data JPA)           |
| **Aspect**          | AOP cross-cutting (activity audit)                     | `@Aspect`, `@Around`                      |
| **Event/Listener**  | Domain events + side-effects                           | `ApplicationEvent`, `@TransactionalEventListener` |
| **Config/Filter**   | Infrastructure (rate-limit, caching, logbook)          | `@Configuration`, `OncePerRequestFilter`  |

### Domain-Driven Package Structure

Packages are organized **by domain**, not by layer:

```
com.lifecontrol.api/
├── company/                # Company + countries/regions/zones
│   ├── controller/
│   │   ├── CompanyController.java
│   │   ├── CompanyCountryController.java    (nested in CompanyController)
│   │   ├── CompanyRegionController.java
│   │   └── CompanyZoneController.java
│   ├── service/
│   │   ├── CompanyService.java
│   │   ├── CompanyCountryService.java
│   │   ├── CompanyRegionService.java
│   │   └── CompanyZoneService.java
│   ├── repository/
│   │   ├── CompanyRepository.java
│   │   ├── CompanyCountryRepository.java
│   │   ├── CompanyRegionRepository.java
│   │   └── CompanyZoneRepository.java
│   ├── model/
│   │   ├── Company.java
│   │   ├── CompanyCountry.java
│   │   ├── CompanyRegion.java
│   │   └── CompanyZone.java
│   ├── dto/
│   │   ├── CompanyRequest.java
│   │   ├── CompanyResponse.java
│   │   ├── CompanyCountry*.java
│   │   ├── CompanyRegion*.java
│   │   └── CompanyZone*.java
│   ├── exception/
│   │   ├── CompanyNotFoundException.java
│   │   ├── DuplicateCompanyException.java
│   │   ├── CompanyCountryNotFoundException.java
│   │   ├── CompanyRegionNotFoundException.java
│   │   ├── CompanyZoneNotFoundException.java
│   │   └── (Duplicate variants)
│   ├── event/
│   │   └── CompanyCreatedEvent.java
│   └── listener/
│       └── KeycloakGroupEventListener.java
├── country/                # Country catalog (enabled/disabled)
│   ├── controller/
│   │   └── CountryController.java
│   ├── service/
│   │   └── CountryService.java
│   ├── repository/
│   │   └── CountryRepository.java
│   ├── model/
│   │   └── Country.java
│   ├── dto/
│   │   ├── CountryRequest.java
│   │   └── CountryResponse.java
│   └── exception/
│       ├── CountryNotFoundException.java
│       └── DuplicateCountryException.java
├── usersadmin/             # Identity provider admin (Keycloak)
│   ├── controller/
│   │   ├── UsersAdminController.java
│   │   └── RolesController.java
│   ├── service/
│   │   └── UsersAdminService.java
│   ├── dto/
│   │   ├── RoleRequest.java, RoleResponse.java
│   │   ├── UserSearchResponse.java, UserAssignmentRequest.java
│   │   ├── PageResponse.java, ChildRoleRequest.java
│   │   └── AttributeValueRequest.java
│   └── identity/
│       ├── IdentityProvider.java           (interface)
│       ├── IdentityProviderException.java  (and subtypes)
│       ├── RoleDto.java, RoleScope.java, UserSearchDto.java
│       └── keycloak/
│           ├── KeycloakIdentityProvider.java  (implementation)
│           ├── KeycloakAdminProperties.java
│           └── KeycloakAdminConfig.java
├── activity/               # Audit trail (AOP-based)
│   ├── controller/
│   │   └── ActivityLogController.java
│   ├── service/
│   │   └── ActivityLogService.java
│   ├── repository/
│   │   ├── ActivityLogRepository.java
│   │   ├── ActivityEventRepository.java
│   │   └── ActivityProcessRepository.java
│   ├── model/
│   │   ├── ActivityLog.java
│   │   ├── ActivityEvent.java
│   │   └── ActivityProcess.java
│   ├── dto/
│   │   ├── ActivityLogResponse.java
│   │   └── ActivityLogFilter.java
│   ├── event/
│   │   └── ActivityLogEvent.java
│   ├── aspect/
│   │   └── ActivityLogAspect.java
│   ├── annotation/
│   │   └── ActivityLog.java
│   ├── listener/
│   │   └── ActivityLogEventListener.java
│   └── util/
│       └── PayloadSanitizer.java
├── common/                 # Shared utilities
│   ├── auth/
│   │   └── CurrentUserContext.java     (request-scoped, JWT-aware)
│   └── model/
│       └── Auditable.java              (createdAt/updatedAt base class)
├── config/                 # Infrastructure configuration
│   ├── security/
│   │   ├── SecurityConfig.java
│   │   └── JwtDecoderConfig.java
│   ├── cache/
│   │   └── CacheConfig.java                (Redis + fallback)
│   ├── ratelimit/
│   │   ├── RateLimitConfig.java
│   │   ├── RateLimitFilter.java            (Bucket4j)
│   │   └── RateLimitProperties.java
│   ├── logbook/
│   │   ├── LogbookConfig.java              (Zalando Logbook)
│   │   └── SensitiveDataSanitizer.java
│   ├── filter/
│   │   ├── ContentCachingFilter.java
│   │   └── ContentCachingConfig.java
│   └── OpenAPIConfig.java
├── validation/             # Custom validators
│   ├── RFCValidator.java
│   └── ValidRFC.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java    (generic 404 base)
│   ├── ConflictException.java            (generic 409 base)
│   └── DuplicateResourceException.java   (409 base for duplicates)
└── LifeControlApiApplication.java
```

---

## API Conventions

### REST Endpoints

- **Prefix**: All domain endpoints use `/api/` prefix
- **ID Type**: `UUID` for all entity identifiers
- **HTTP Methods**: Follow REST conventions (GET, POST, PUT, PATCH, DELETE)
- **Pagination**: Use Spring Data `Pageable` with `@PageableDefault` — returns `Page<T>` envelope
- **Nested Resources**: Hierarchical paths for sub-resources (e.g. `/api/companies/{id}/countries/{cid}/regions`)
- **Soft Delete**: DELETE sets `enabled = false`; GET supports `?includeDisabled=true` to include them
- **PATCH**: Used for re-enabling soft-deleted resources

### Endpoint Map

| Prefix                                          | Access                    | Description                            |
|-------------------------------------------------|---------------------------|----------------------------------------|
| `/api/companies`                                | `lc-admin\|lc-company\|lc-company-read` | Company CRUD + search (paginated; `lc-company-read` is read-only)  |
| `/api/companies/{id}/countries`                 | `lc-admin\|lc-company\|lc-company-country\|lc-company-country-read` (read) / `lc-admin\|lc-company\|lc-company-country` (write, method-level) | Company-country associations |
| `/api/companies/{id}/countries/{cid}/regions`   | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-region-read` (read) / `lc-admin\|lc-company\|lc-company-country\|lc-company-region` (write, method-level) | Regions within a company-country   |
| `/api/companies/{id}/countries/{cid}/regions/{rid}/zones` | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone\|lc-company-zone-read` (read) / `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone` (write, method-level) | Zones within a region              |
| `/api/companies/{id}/countries/{cid}/regions/{rid}/zones/{zid}/stores` | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone\|lc-company-store\|lc-company-store-read` (read) / `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone\|lc-company-store` (write, method-level) | Stores within a zone               |
| `/api/countries, /api/measure-units, /api/payment-methods, /api/statuses, /api/status-types` | authenticated (read) / `lc-admin\|lc-country\|lc-measure-unit\|lc-payment-method\|lc-status\|lc-status-type` (write) | Catalog CRUD — method-level `@PreAuthorize` (`Roles.ADMIN` + domain role for writes) |
| `/api/activity-logs`                            | `lc-admin`                 | Audit trail query (paginated, filterable) |
| `/api/purchase-orders`                          | authenticated (read) / `lc-admin\|lc-sales` (write, method-level) | Purchase order CRUD + line items (sales "orders" domain) |
| `/api/goods-receipts`                           | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone\|lc-company-store\|lc-receiving` (write) / same set + `lc-company-store-read` (read, method-level) | Goods receipts (receptions): register against a purchase order (`POST`, 201) and read (paginated list + `GET /{id}`); non-admins are scoped to their `company_store_id` claim |
| `/api/suppliers`                                | authenticated (read) / `lc-admin\|lc-product-supplier` (write, method-level) | Supplier CRUD (soft delete) |
| `/api/products`                                 | authenticated (read) / `lc-admin` (write, method-level) / `lc-admin\|lc-product-supplier` (supplier assignment) / `lc-admin\|lc-sales` (variants, method-level) | Product catalog, supplier assignments, product variants |
| `/api/product-variants`                         | `lc-admin\|lc-sales`       | Product variant search |
| `/api/customers, /api/sales-orders, /api/shifts, /api/promotions` | `lc-admin\|lc-sales` (method-level) | Sales domain — customers, sales orders, shifts, promotions |
| `/api/users-admin/users`                        | `admin`                   | Keycloak user search, roles, attributes |
| `/api/users-admin/roles`                        | `admin`                   | Keycloak realm/client role CRUD       |

> Role name literals live in `com.lifecontrol.api.common.security.Roles` and must be referenced from
> `@PreAuthorize` (e.g. `Roles.COUNTRY`, `Roles.ADMIN`). Bare `lc-*` / `life-control-*`
> literals must not appear in controller annotations.

### OpenAPI Documentation

Use OpenAPI annotations (`@Tag`, `@Operation`, `@ApiResponse`) on all controllers.

```java
@RestController
@RequestMapping("/api/companies")
@Tag(name = "Company Management", description = "API for managing companies")
@PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-read')")
public class CompanyController {

    @GetMapping
    @Operation(summary = "Get all companies", description = "Returns a paginated list, optionally filtered by search term")
    public ResponseEntity<Page<CompanyResponse>> getAllCompanies(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(companyService.getAllCompanies(pageable, search));
    }
}
```

### HTTP Status Codes

| Status              | Usage                                           |
|---------------------|-------------------------------------------------|
| `200 OK`            | Successful GET, PUT, PATCH                      |
| `201 Created`       | Successful POST                                 |
| `204 No Content`    | Successful DELETE                               |
| `400 Bad Request`   | Validation errors (`MethodArgumentNotValidException`) |
| `403 Forbidden`     | `AccessDeniedException` (insufficient role)     |
| `404 Not Found`     | Resource not found                              |
| `409 Conflict`      | Duplicate resource (RFC, companyKey, code)      |
| `429 Too Many Requests` | Rate limit exceeded (users-admin endpoints) |
| `503 Service Unavailable` | Identity provider connection failure      |

---

## Coding Patterns (NO Lombok)

**This project does NOT use Lombok.** Use modern Java features instead.

### Constructor Injection (explicit)

```java
// ✅ CORRECTO — constructor injection manual
@RestController
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }
}

// ❌ INCORRECTO — no usar @RequiredArgsConstructor ni @Autowired
```

### Records for DTOs

```java
// ✅ CORRECTO — record puro para DTOs
public record CompanyResponse(
    UUID id,
    String companyKey,
    String companyName,
    Integer tipoPersonaId,
    String razonSocial,
    String rfc,
    String phone,
    String email,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

// ❌ INCORRECTO — no usar @Data, @Builder, @Value
```

Record request DTOs can include compact constructors for defaults:

```java
public record CompanyRequest(
    @NotBlank String companyKey,
    @NotBlank @ValidRFC String rfc,
    // ...
    Boolean enabled
) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
```

### Entities with Manual Getters/Setters + Builder

```java
// ✅ CORRECTO — Entity con getters/setters + builder estático
@Entity
@Table(name = "companies")
public class Company extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    // Default constructor for JPA
    public Company() {}

    // Manual getters / setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Company company = new Company();
        public Builder companyName(String name) { company.companyName = name; return this; }
        // ...
        public Company build() { return company; }
    }
}
```

### Auditable Base Class

All entities extend `Auditable` for automatic `createdAt`/`updatedAt` timestamps via `@PrePersist`/`@PreUpdate`:

```java
@MappedSuperclass
public abstract class Auditable {
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters — public
    // Setters — protected (builder access only)
}
```

### Use `var` for Local Variables

```java
// ✅ CORRECTO — inferencia de tipo con var
var company = companyRepository.findById(id);
var companies = companyRepository.findAll();
for (var c : companies) { ... }
```

### Transaction Management

```java
@Service
public class CompanyService {

    @Transactional(readOnly = true)
    public Page<CompanyResponse> getAllCompanies(Pageable pageable, String search) {
        return companyRepository.findBySearchTerm(search.trim(), pageable)
                .map(this::toResponse);
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        var company = Company.builder()
                .companyKey(request.companyKey())
                .companyName(request.companyName())
                .build();
        var saved = companyRepository.save(company);
        eventPublisher.publishEvent(new CompanyCreatedEvent(this, saved.getId(), ...));
        return toResponse(saved);
    }
}
```

### Exception Hierarchy (generic categories + domain subclasses)

Exceptions live in `com.lifecontrol.api.exception` as three generic, parametrizable categories:

```java
public class ResourceNotFoundException extends RuntimeException {          // 404
    public ResourceNotFoundException(String message) { super(message); }
    public <T> ResourceNotFoundException(Class<T> resource, UUID id) {
        super(resource.getSimpleName() + " not found with id: " + id);
    }
}

public class ConflictException extends RuntimeException {                   // 409
    public ConflictException(String message) { super(message); }
}

public class DuplicateResourceException extends ConflictException {        // 409
    public DuplicateResourceException(String message) { super(message); }
}
```

Domain exceptions extend the matching generic base and stay as thin, semantic aliases:

```java
public class CompanyNotFoundException extends ResourceNotFoundException {
    public CompanyNotFoundException(UUID id) {
        super("Company not found with id: " + id);
    }
}

public class DuplicateCountryException extends DuplicateResourceException {
    public DuplicateCountryException(String message) { super(message); }
}

public class InvalidStatusTransitionException extends ConflictException {
    public InvalidStatusTransitionException(String from, String to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
```

`GlobalExceptionHandler` resolves them **by inheritance** — one handler per category, never one per class.
The identity-provider hierarchy (`IdentityProviderException` + its sealed subtypes) is intentionally
kept separate because those errors carry provider-specific semantics and status codes (404/409/503).

### CurrentUserContext for Auth-Aware Services

For services that need to check the current user's roles or company access at the domain level (not just controller `@PreAuthorize`):

```java
@Service
public class CompanyService {
    private final CurrentUserContext currentUserContext;

    @Transactional(readOnly = true)
    public Page<CompanyResponse> getAllCompanies(Pageable pageable, String search) {
        if (!currentUserContext.isAdmin()) {
            // Country-role user: scope to assigned companies via company_id claim
            var companyIds = currentUserContext.getCompanyIds();
            // ...
        }
    }
}
```

`CurrentUserContext` is a request-scoped proxy that lazily extracts `company_id`, roles, `sub`, and `preferred_username` from the JWT.

---

## Validation

### Jakarta Bean Validation

Use `jakarta.validation` constraints on record DTOs. Validation error responses include per-field error maps with `status`, `message`, `path`, `timestamp`, and `correlationId`.

```java
public record CompanyRequest(
    @NotBlank(message = "companyKey is required")
    @Size(max = 50) String companyKey,

    @NotBlank @ValidRFC String rfc,

    @Min(1) @Max(5) Integer tipoPersonaId,

    @Email @Size(max = 100) String email
) {}
```

```java
@PostMapping
public ResponseEntity<CompanyResponse> createCompany(@Valid @RequestBody CompanyRequest request) {
    // ...
}
```

### Custom Validator: @ValidRFC

Validates Mexican RFC format (3-4 letters + 6 digits + 3 alphanumeric). Accepts `null` — pair with `@NotBlank` for required fields.

---

## Security & Authorization

Role-based access:

| Role                   | Authority                          | Scope                          |
|------------------------|------------------------------------|--------------------------------|
| `lc-admin`             | `ROLE_lc-admin`                    | Full CRUD on all domains, activity log access, purchase order writes (superseded `life-control-admin`) |
| `lc-company`           | `ROLE_lc-company`                  | Scoped by `company_id` JWT claim, full CRUD on assigned companies |
| `lc-company-read`      | `ROLE_lc-company-read`             | Read-only GET access to companies, scoped by `company_id` JWT claim |
| `lc-company-country`   | `ROLE_lc-company-country`          | Scoped by `company_id`, CRUD on company-country associations |
| `lc-company-country-read` | `ROLE_lc-company-country-read`  | Read-only GET on company-country associations, scoped by `company_id` |
| `lc-company-region`    | `ROLE_lc-company-region`           | Scoped by `company_id`, CRUD on company-country regions |
| `lc-company-region-read` | `ROLE_lc-company-region-read`   | Read-only GET on regions, scoped by `company_id` |
| `lc-company-zone`      | `ROLE_lc-company-zone`             | Scoped by `company_id`, CRUD on company regions' zones |
| `lc-company-zone-read` | `ROLE_lc-company-zone-read`        | Read-only GET on zones, scoped by `company_id` |
| `lc-company-store`     | `ROLE_lc-company-store`            | Scoped by `company_id`, CRUD on company zones' stores |
| `lc-company-store-read` | `ROLE_lc-company-store-read`     | Read-only GET on stores, scoped by `company_id` |
| `lc-receiving`         | `ROLE_lc-receiving`                | Store-scoped reception (goods receipts): create + read, scoped by the `company_store_id` claim path — see the claim dependency in [Keycloak Role Setup](#keycloak-role-setup) |
| `lc-country`           | `ROLE_lc-country`                  | Write access to country catalog (read requires any authenticated user) |
| `lc-status`            | `ROLE_lc-status`                   | Write access to status values (read requires any authenticated user) |
| `lc-status-type`       | `ROLE_lc-status-type`              | Write access to status types (read requires any authenticated user) |
| `lc-payment-method`    | `ROLE_lc-payment-method`           | Write access to payment methods (read requires any authenticated user) |
| `lc-measure-unit`      | `ROLE_lc-measure-unit`             | Write access to measure units (read requires any authenticated user) |
| `lc-product-supplier`  | `ROLE_lc-product-supplier`         | Supplier CRUD (soft delete) and product-supplier assignment endpoints (supersedes `life-control-country`) |
| `lc-sales`             | `ROLE_lc-sales`                    | Sales domain: customers, sales orders, purchase orders, shifts, promotions, product variants |
| `life-control-admin`   | `ROLE_life-control-admin`          | **Legacy** — no new assignments, pending Keycloak migration; still accepted by `CurrentUserContext#isAdmin()` for backward compatibility |
| `life-control-country` | `ROLE_life-control-country`        | **Legacy** — no new assignments, pending Keycloak migration; still accepted by `CurrentUserContext#isCountryRole()` for backward compatibility |
| `admin`                | `ROLE_admin`                       | Users-admin endpoints (Keycloak admin) |

### Architecture

1. **JWT Decoder** (`JwtDecoderConfig`): Validates signature via JWK Set URI and timestamp, and the `iss` claim against an issuer allowlist (`JwtIssuerAllowlistValidator`, mirroring the gateway). The allowlist comes from `keycloak.allowed-issuers` and by default includes the public browser issuer (`KEYCLOAK_ISSUER_URI`) plus the internal issuer (`keycloak.uri`, from `KEYCLOAK_URI`). This lets tokens minted for the browser issuer pass while the signature is still verified against the internal JWK set.
2. **Role Mapping**: `realm_access.roles` → `ROLE_<name>` authorities via custom `JwtAuthenticationConverter`.
3. **Company ID Claim**: `company_id` claim (single UUID or comma-separated) parsed by `CurrentUserContext` for scoped access.
4. **Controller Guards**: `@PreAuthorize` at class level on CompanyController. Method-level `@PreAuthorize` on CompanyCountryController: write endpoints (`POST`, `PUT`, `DELETE`) require `lc-admin`/`lc-company`/`lc-company-country`; read endpoint (`GET`) also allows `lc-company-country-read`.
5. **Service-Level Checks**: `currentUserContext.verifyCompanyAccess(id)` in service logic.
6. **Endpoint-Level Rules**: `SecurityConfig` enforces `ROLE_admin` for `/api/users-admin/**`.

```java
// SecurityConfig
.authorizeHttpRequests(auth -> auth
    .requestMatchers(PUBLIC_URLS).permitAll()
    .requestMatchers("/api/users-admin/**").hasAuthority("ROLE_admin")
    .requestMatchers("/api/**").authenticated()
    .anyRequest().permitAll())
```

### Role Setup in Keycloak

| Property          | Value                    |
|-------------------|--------------------------|
| Realm             | `life-control-realm`     |
| Role Name         | `life-control-admin`     |
| Role Type         | Realm Role               |
| Additional Roles  | `admin`, `lc-company` |

On company creation, a Keycloak group `company-<sanitized-name>` is auto-created via `@TransactionalEventListener`.

---

## Exception Handling

### GlobalExceptionHandler

Standardized error responses with `status`, `message`, `path`, `timestamp`, and `correlationId` (trace ID from Micrometer Tracing). Handlers are declared **by category** and resolve domain exceptions by inheritance (no per-class handlers).

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)          // 404 — all *NotFoundException
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)                   // 409 — duplicates + state conflicts
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidSalesOrderChargeException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {   // 400
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IdentityProviderNotFoundException.class)   // identity provider category
    public ResponseEntity<ErrorResponse> handleIdentityProviderNotFound(IdentityProviderNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IdentityProviderConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConflict(IdentityProviderConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IdentityProviderConnectionException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConnection(IdentityProviderConnectionException ex) {
        logger.error("Identity provider connection failure", ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider temporarily unavailable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)       // 400 — validation
    public ResponseEntity<ValidationErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        var errors = new HashMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(
                new ValidationErrorResponse(400, "Validation failed", errors, getCurrentPath(), LocalDateTime.now(), getCorrelationId()));
    }

    @ExceptionHandler(AccessDeniedException.class)                // 403
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(Exception.class)                            // 500 — fallback
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unhandled exception", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    public record ErrorResponse(int status, String message, String path, LocalDateTime timestamp, String correlationId) {}
    public record ValidationErrorResponse(int status, String message, Map<String, String> errors, String path, LocalDateTime timestamp, String correlationId) {}
}
```

Handler categories: NotFound (`ResourceNotFoundException`), Duplicate/Conflict (`ConflictException`, incl. `DuplicateResourceException`), BadRequest (`IllegalArgumentException`, `InvalidSalesOrderChargeException`), IdentityProvider (`IdentityProviderNotFoundException`, `IdentityProviderConflictException`, `IdentityProviderConnectionException`), Validation (`MethodArgumentNotValidException`), AccessDenied (`AccessDeniedException`), and the generic `Exception` fallback.

---

## Cross-Cutting Infrastructure

### Rate Limiting (Bucket4j)

- Applied to `/api/users-admin/*` via `RateLimitFilter` at `HIGHEST_PRECEDENCE`
- Token-bucket per endpoint with configurable capacity and duration
- Internal IP whitelist (CIDR support)
- Adds `X-RateLimit-*` headers; returns 429 with `Retry-After`

```properties
app.rate-limit.enabled=true
app.rate-limit.internal-ip-whitelist=127.0.0.1,::1
app.rate-limit.endpoints./api/users-admin/users.max-requests=60
app.rate-limit.endpoints./api/users-admin/users.duration=1m
```

### HTTP Request/Response Logging (Zalando Logbook)

- Filters sensitive headers (Authorization, cookies) and body fields (password, secret, token)
- Strategy: `status-only` (logs only when non-2xx)

### Activity Audit Trail (AOP)

- `@Aspect` auto-logs every `@RestController` invocation
- Resolves process from package (e.g. `company` → `COMPANY`) and event from HTTP method (GET → READ, POST → CREATE, etc.)
- Override with `@ActivityLog(process = "CUSTOM", event = "CUSTOM")`
- Best-effort: never fails the original request
- Requires `ContentCachingFilter` to read request body multiple times

### Caching (Spring Cache)

- Redis when available, in-memory `ConcurrentMapCache` fallback
- Cached regions: `countries`, `companyRegions` (1-hour TTL in Redis)

### Observability

| Concern             | Implementation                     |
|---------------------|------------------------------------|
| Metrics             | Micrometer + Prometheus (`/actuator/prometheus`) |
| Tracing             | Micrometer Tracing + Brave + Zipkin |
| Log Aggregation     | Loki (Loki4j appender)             |
| Correlation IDs     | `traceId`/`spanId` in MDC, included in error responses |
| Health              | `/actuator/health`                  |

---

## Testing Patterns

### Integration Tests (Testcontainers PostgreSQL)

Tests that validate real persistence or SQL constraints extend
`com.lifecontrol.api.support.AbstractPostgresIntegrationTest`. It starts a shared
`PostgreSQLContainer` per JVM, repoints the datasource, and enables Flyway so the
schema is built from the production migrations (`V1`→`V3`).

```java
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sales Order Integration Tests")
class SalesOrderIntegrationTest extends AbstractPostgresIntegrationTest {
    // ...
}
```

Requires a running Docker daemon. Use the H2 default stack for `@WebMvcTest`
slices, unit tests, and AOP/cache tests backed by mocked repositories.

### Controller Tests (MockMvc Standalone)

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyController Tests")
class CompanyControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private CompanyService companyService;
    @InjectMocks private CompanyController companyController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    @Nested
    @DisplayName("PUT /api/companies/{id}")
    class UpdateCompanyTests {

        @Test
        @DisplayName("should return 200 OK with updated company")
        void updateCompany_Success() throws Exception {
            when(companyService.updateCompany(eq(id), any(CompanyRequest.class)))
                    .thenReturn(testCompanyResponse);

            mockMvc.perform(put("/api/companies/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCompanyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyName").value("Test Company"));
        }
    }
}
```

### Service Tests

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService Tests")
class CompanyServiceTest {

    @Mock private CompanyRepository companyRepository;
    @InjectMocks private CompanyService companyService;

    @Nested
    @DisplayName("getCompanyById")
    class GetCompanyByIdTests {

        @Test
        @DisplayName("should return company when found")
        void shouldReturnCompany_WhenFound() {
            var id = UUID.randomUUID();
            var company = Company.builder().id(id).companyName("Test").build();
            when(companyRepository.findById(id)).thenReturn(Optional.of(company));

            var result = companyService.getCompanyById(id);

            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Test");
        }
    }
}
```

### Security Tests (MockMvc + @WithMockUser)

```java
@ExtendWith(MockitoExtension.class)
class CompanyControllerSecurityTest {
    // Tests for @PreAuthorize with mock JWT roles
    // Verify 403 when missing required role
}
```

---

## Development Commands

### Build and Run

```bash
# Build (compile + test)
./gradlew build

# Run application
./gradlew bootRun

# Run tests
./gradlew test

# Package JAR
./gradlew bootJar

# Clean build
./gradlew clean build
```

### Test Selection

```bash
# Specific test class
./gradlew test --tests "com.lifecontrol.api.company.controller.CompanyControllerTest"

# Specific nested test
./gradlew test --tests "com.lifecontrol.api.company.controller.CompanyControllerTest.UpdateCompanyTests"

# Skip tests
./gradlew build -x test
```

### Profiles

```bash
# Dev (default)
./gradlew bootRun

# Production
./gradlew bootRun --args='--spring.profiles.active=prod'

# Test with profile
SPRING_PROFILES_ACTIVE=test ./gradlew test
```

---

## Database

### Migrations (Flyway)

The database schema is managed by **Flyway** — the single source of truth for DDL.
Migrations live in `src/main/resources/db/migration/` and are versioned:

| Migration | Description |
|-----------|-------------|
| `V1__baseline_schema.sql` | Full baseline schema (all tables in dependency order) |
| `V2__seed_countries.sql`  | Seed data: MX/CO/US countries (idempotent) |
| `V3__seed_reference_data.sql` | Seed data: status types/statuses, activity processes/events, measure units, payment methods, "Cliente General" customer (idempotent) |

Key settings (`application.properties`):

- **DDL**: `spring.jpa.hibernate.ddl-auto=none` (schema managed via Flyway migrations)
- **SQL init**: `spring.sql.init.mode=never` (`schema.sql` no longer used)
- **Baseline**: `spring.flyway.baseline-on-migrate=true` + `spring.flyway.baseline-version=1` — existing DBs with data are baselined at v1 (no recreation); only new migrations apply.
- **Tests**: default test stack (`application-test.properties`) uses H2 with `spring.flyway.enabled=false` + `create-drop` for pure slices (`@WebMvcTest`), unit tests, and AOP/cache tests with mocked repositories. Integration tests that exercise real persistence/constraints extend `AbstractPostgresIntegrationTest` (Testcontainers `PostgreSQLContainer`, shared singleton), which overrides the datasource and **enables Flyway** so the schema comes from `V1`→`V3` exactly as in production.

To add a schema change: create a new `V{n}__description.sql` file. Never edit an already-applied migration (Flyway checksums will fail).

### Seed Data (catalog / reference rows)

Reference/catalog data is seeded by **Flyway only** — a single source of truth:

- **Flyway**: `countries` via `V2__seed_countries.sql`; all other reference domains (`status_types`/`statuses` for purchase + sales orders, `activity_processes`/`activity_events`, `measure_units`, `payment_methods`, and the default "Cliente General" customer) via `V3__seed_reference_data.sql`.
- **No ApplicationRunner seeding**: the previous `*/config/*Initializer.java` beans and `customer/config/CustomerSeedRunner.java` were removed.

V3 notes:
- Every insert is `INSERT … SELECT … WHERE NOT EXISTS`-guarded on a natural key (the customer is guarded on its fixed id `00000000-0000-0000-0000-000000000001`), so it applies idempotently under `baseline-on-migrate=true`.
- V3 also performs the legacy sales-order status rename (`Borrador`→`Draft`, `Enviada`→`Pending`, `Cerrada`→`Completed`, `Cancelada`→`Cancelled`, `Pendiente`→`Pending`, `Agregado`→`Added`, `Cancelado`→`Cancelled`) for both `SALES_ORDER` and `SALES_ORDER_ITEM`, deterministically, skipping when the target name already exists (never violating `UNIQUE(status_type_id, status_name)`).
- V3 runs on the PostgreSQL Testcontainers stack used by `AbstractPostgresIntegrationTest` subclasses (Flyway enabled). On the H2 stack (`spring.flyway.enabled=false`) it does not run; `SalesOrderIntegrationTest` keeps idempotent find-or-create seeding in `setUp()` so it works on both.

### Schema

Tables include: `companies`, `countries`, `addresses`, `company_countries`, `company_regions`, `company_zones`, `company_stores`, `suppliers`, `products`, `product_suppliers`, `activity_processes`, `activity_events`, `activity_logs`, `status_types`, `statuses`, `measure_units`, `payment_methods`, `purchase_orders`, `purchase_order_details`, `user_preferences`, `customers`, `product_variants`, `promotions`, `shifts`, `sales_orders`, `sales_order_items`.

### Key Columns

All tables use `UUID` primary keys, `created_at`/`updated_at` timestamps. Soft-delete uses `enabled` boolean column.

---

## Docker

### Build & Deploy (single service, dev)

Desde `life-control-api/`:

```bash
# 1. Compilar JAR (sin tests para velocidad)
./gradlew bootJar --no-daemon -Pprofile=dev -x test

# 2. Rebuild imagen Docker (solo este servicio)
cd ../docker
docker compose -f docker-compose.yml -f docker-compose.override.yml \
  --env-file .env.dev build --no-cache lifecontrol-api

# 3. Redeploy contenedor
docker compose -f docker-compose.yml -f docker-compose.override.yml \
  --env-file .env.dev up -d lifecontrol-api

# 4. Verificar
docker compose -f docker-compose.yml -f docker-compose.override.yml \
  --env-file .env.dev ps lifecontrol-api
```

Para staging/prod, cambiar `.env.dev` por `.env.staging` o `.env.prod` y omitir `docker-compose.override.yml`.

### Build & Deploy (all services)

```bash
cd docker
./scripts/deploy.sh dev build      # compila JARs + Angular
./scripts/deploy.sh dev build-images  # buildea imágenes Docker
./scripts/deploy.sh dev restart       # stop + start todos los servicios
```

### Environment Variables

| Variable                          | Description              | Default                                    |
|-----------------------------------|--------------------------|--------------------------------------------|
| `SERVER_PORT`                     | HTTP port                | `8082`                                     |
| `DATABASE_URL`                    | JDBC URL                 | `jdbc:postgresql://lifecontrol-postgres:5432/lifecontrol` |
| `DATABASE_USERNAME`               | DB user                  | `lifecontrol_user`                         |
| `DATABASE_PASSWORD`               | DB password              | (required)                                 |
| `REDIS_HOST`                      | Redis host               | `localhost`                                |
| `REDIS_PORT`                      | Redis port               | `6379`                                     |
| `LOKI_URL`                        | Loki push URL            | `http://loki:3100/loki/api/v1/push`        |
| `KEYCLOAK_URI`                    | Keycloak realm URL (internal, for JWK fetch) | `http://lifecontrol-dev-keycloak:8080/realms/life-control-realm` |
| `KEYCLOAK_ISSUER`                 | JWT issuer (defaults to `KEYCLOAK_URI`) | `<KEYCLOAK_URI>`    |
| `KEYCLOAK_ISSUER_URI`             | Public Keycloak issuer used by the browser | `http://localhost:8181/realms/life-control-realm` |
| `KEYCLOAK_ALLOWED_ISSUERS`        | Comma-separated issuer allowlist (overrides the default) | `<KEYCLOAK_ISSUER_URI>,<KEYCLOAK_URI>` |
| `KEYCLOAK_ADMIN_SERVER_URL`       | Keycloak admin URL       | `http://lifecontrol-dev-keycloak:8080`     |
| `KEYCLOAK_ADMIN_CLIENT_SECRET`    | Admin client secret      | (required for admin ops)                   |

---

## Keycloak Role Setup

### Required Roles

Client roles (`life-control-client`) are provisioned idempotently by
`docker/scripts/keycloak-setup.sh`; realm roles must be created manually (`life-control-realm`):

**Client roles (provisioned by `keycloak-setup.sh`):**

- **`lc-admin`** — Full CRUD on all domains, activity log access, purchase order writes (supersedes `life-control-admin`)
- **`lc-company`** — Scoped company CRUD (filtered by `company_id` JWT claim)
- **`lc-company-read`** — Read-only GET access to companies, scoped by `company_id` JWT claim
- **`lc-company-country`** / **`lc-company-country-read`** — Company-country CRUD / read-only GET, scoped by `company_id` JWT claim
- **`lc-company-region`** / **`lc-company-region-read`** — Region CRUD / read-only GET, scoped by `company_id`
- **`lc-company-zone`** / **`lc-company-zone-read`** — Zone CRUD / read-only GET, scoped by `company_id`
- **`lc-company-store`** / **`lc-company-store-read`** — Store CRUD / read-only GET, scoped by `company_id`
- **`lc-receiving`** — Store-scoped reception (goods receipts): create + read. **Deployment dependencies: API-only until the frontend wires it, the role alone is not sufficient, and the gateway route is provisioned separately — see the warnings below.**
- **`lc-country`**, **`lc-status`**, **`lc-status-type`**, **`lc-payment-method`**, **`lc-measure-unit`** — write access to catalog domains (reads require any authenticated user)
- **`lc-product-supplier`** — Supplier CRUD and product-supplier assignment endpoints (supersedes `life-control-country`)
- **`lc-sales`** — Sales domain: customers, sales orders, purchase orders, shifts, promotions, product variants

> **Deployment dependency — `lc-receiving` alone is not sufficient.** `lc-receiving` is a
> store-scoped role. `CurrentUserContext#verifyCompanyStoreAccess` verifies the claim path up to
> the broadest role granted, so for a principal holding **only** `lc-receiving` (or only
> `lc-company-store` / `lc-company-store-read`) the full hierarchy of **claims** is verified —
> `company_id` → `company_country_id` → `company_region_id` → `company_zone_id` →
> `company_store_id`. The parent levels are verified against claims, never against the parent
> roles (`verifyLevel` never consults roles), so the operator must ensure those five claims reach
> the token through the same mechanism the existing store roles (`lc-company-store` /
> `lc-company-store-read`) already rely on. Granting the role
> without the claims yields `403 Forbidden`, not access — the role is necessary but not
> sufficient.
>
> **API-only until the frontend slice wires it.** The Angular client does not know this role yet:
> `life-control-app-angular/src/core/security/roles.ts` has no `LC_RECEIVING`, and the receipts
> route is currently admin-gated. Granting `lc-receiving` in Keycloak therefore does not, on its
> own, surface any reception UI to a non-admin user — the role is API-only until the frontend
> slice wires it.
>
> **Gateway route is provisioned separately.** `/api/goods-receipts/**` must be routed explicitly
> by the API gateway. A deployment that fronts the API with the gateway cannot reach those
> endpoints — role and backend endpoint notwithstanding — until that route exists.
>
> **Not verifiable from this repository:** the `company_*` claims are not provisioned by any file
> in this tree (no claim mapper, protocol mapper or group-to-claim configuration is versioned
> here), so the claim-mapping side of this dependency must be confirmed against the Keycloak
> realm configuration.

**Realm roles (manual):**

- **`life-control-admin`** — **Legacy** — no new assignments; kept for backward compatibility via `CurrentUserContext#isAdmin()` until the Keycloak migration to `lc-*` client roles is complete
- **`life-control-country`** — **Legacy** — no new assignments; kept for backward compatibility via `CurrentUserContext#isCountryRole()` until the Keycloak migration to `lc-*` client roles is complete
- **`admin`** — Users-admin endpoints (role/user management)

### Company Groups (Auto-Created)

When a company is created, a `company-<sanitized-name>` group is auto-created in Keycloak via `KeycloakGroupEventListener`. The group carries the company's UUID as an attribute.

### How It Works

1. Keycloak issues JWT with `realm_access.roles` and `company_id` claim
2. `JwtDecoderConfig` → `JwtAuthenticationConverter` maps roles to `ROLE_<name>` authorities
3. `SecurityConfig` enforces endpoint-level rules
4. `@PreAuthorize` on controllers checks specific roles
5. `CurrentUserContext` extracts `company_id` for scoped access
6. Users-admin endpoints require `ROLE_admin`

---

## SDD (Spec-Driven Development)

This project uses SDD for structured feature development. SDD artifacts live in `life-control-api/sdd/<change-name>/` (OpenSpec mode).

### Existing Changes

| Change              | Status     |
|---------------------|------------|
| `company-zones-backend` | (in progress) |

See `AGENTS.md` (root) for the skill registry and the SDD workflow. Component conventions and
security gates for changes to this API live in
[`project-conventions/references/api.md`](../.agents/skills/project-conventions/references/api.md).
