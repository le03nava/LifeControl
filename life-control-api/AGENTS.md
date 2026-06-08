# Life Control API — Developer Guide

## Tech Stack

| Component        | Technology                              |
|------------------|-----------------------------------------|
| Framework        | Spring Boot 3.4.0                      |
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
│   ├── config/
│   │   └── ActivityLogInitializer.java
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
│   └── GlobalExceptionHandler.java
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
| `/api/companies`                                | `life-control-admin\|lc-company\|lc-company-read` | Company CRUD + search (paginated; `lc-company-read` is read-only)  |
| `/api/companies/{id}/countries`                 | `life-control-admin\|lc-company\|lc-company-read` | Company-country associations (read-only for `lc-company-read`) |
| `/api/companies/{id}/countries/{cid}/regions`   | `life-control-admin\|lc-company` | Regions within a company-country   |
| `/api/companies/{id}/countries/{cid}/regions/{rid}/zones` | life-control-admin\|lc-company | Zones within a region              |
| `/api/countries`                                | authenticated             | Country catalog CRUD                  |
| `/api/activity-logs`                            | `life-control-admin`      | Audit trail query (paginated, filterable) |
| `/api/users-admin/users`                        | `admin`                   | Keycloak user search, roles, attributes |
| `/api/users-admin/roles`                        | `admin`                   | Keycloak realm/client role CRUD       |

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

### Exception Hierarchy (flat RuntimeException)

```java
// ✅ CORRECTO — excepción base directa (no sealed)
public class CompanyNotFoundException extends RuntimeException {
    public CompanyNotFoundException(UUID id) {
        super("Company not found with id: " + id);
    }
}

public class DuplicateCompanyException extends RuntimeException {
    public DuplicateCompanyException(String message) {
        super(message);
    }
}
```

No sealed hierarchy needed — keep exceptions flat and specific per domain.

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
    @NotBlank(message = "companyKey es requerido")
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

Three tiers of access:

| Role                   | Authority                          | Scope                          |
|------------------------|------------------------------------|--------------------------------|
| `lc-admin`             | `ROLE_lc-admin`                    | Full CRUD on companies, activity logs (replaces `life-control-admin`) |
| `lc-company`           | `ROLE_lc-company`                  | Scoped by `company_id` JWT claim, full CRUD on assigned companies |
| `lc-company-country`   | `ROLE_lc-company-country`          | Scoped by `company_id`, CRUD on company-country associations |
| `lc-company-read`      | `ROLE_lc-company-read`             | Read-only GET access, scoped by `company_id` JWT claim |
| `admin`                | `ROLE_admin`                       | Users-admin endpoints (Keycloak admin) |

### Architecture

1. **JWT Decoder** (`JwtDecoderConfig`): Validates signature via JWK Set URI, timestamp only (no issuer validation — allows multi-env Keycloak URIs).
2. **Role Mapping**: `realm_access.roles` → `ROLE_<name>` authorities via custom `JwtAuthenticationConverter`.
3. **Company ID Claim**: `company_id` claim (single UUID or comma-separated) parsed by `CurrentUserContext` for scoped access.
4. **Controller Guards**: `@PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-read')")` on CompanyController. Method-level overrides restrict write endpoints to `lc-admin`/`lc-company` (and `lc-company-country` for country mutations).
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

Standardized error responses with `status`, `message`, `path`, `timestamp`, and `correlationId` (trace ID from Micrometer Tracing).

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CompanyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CompanyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ErrorResponse(404, ex.getMessage(), getCurrentPath(), LocalDateTime.now(), getCorrelationId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var errors = new HashMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(
                new ValidationErrorResponse(400, "Validation failed", errors, getCurrentPath(), LocalDateTime.now(), getCorrelationId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(...);
    }

    // Generic fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(...);
    }

    public record ErrorResponse(int status, String message, String path, LocalDateTime timestamp, String correlationId) {}
    public record ValidationErrorResponse(int status, String message, Map<String, String> errors, String path, LocalDateTime timestamp, String correlationId) {}
}
```

Handles: `CompanyNotFoundException`, `DuplicateCompanyException`, `CountryNotFoundException`, `CompanyRegionNotFoundException`, `CompanyZoneNotFoundException`, `CompanyCountryNotFoundException`, `IdentityProviderNotFoundException`, `IdentityProviderConflictException`, `IdentityProviderConnectionException`, `IllegalArgumentException`, `MethodArgumentNotValidException`, `AccessDeniedException`.

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

### Schema

Defined in `src/main/resources/schema.sql` — includes:

| Table              | Description                        |
|--------------------|------------------------------------|
| `companies`        | Company entities                   |
| `countries`        | Country catalog                    |
| `company_countries` | M:N company-country associations  |
| `company_regions`  | Regions within a company-country   |
| `company_zones`    | Zones within a region              |
| `activity_processes` | Reference: audit process types |
| `activity_events`  | Reference: audit event types       |
| `activity_logs`    | Immutable audit trail              |

- **Init Mode**: `spring.sql.init.mode=always` (recreates on startup for dev)
- **DDL**: `spring.jpa.hibernate.ddl-auto=none` (schema managed via SQL files)

### Key Columns

All tables use `UUID` primary keys, `created_at`/`updated_at` timestamps. Soft-delete uses `enabled` boolean column.

---

## Docker

### Build

```bash
# Build JAR locally first
./gradlew bootJar

# Build Docker image
docker build -t life-control-api:latest .

# Run
docker run -p 8082:8082 life-control-api:latest
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
| `KEYCLOAK_URI`                    | Keycloak realm URL       | `http://lifecontrol-dev-keycloak:8080/realms/life-control-realm` |
| `KEYCLOAK_ADMIN_SERVER_URL`       | Keycloak admin URL       | `http://lifecontrol-dev-keycloak:8080`     |
| `KEYCLOAK_ADMIN_CLIENT_SECRET`    | Admin client secret      | (required for admin ops)                   |

---

## Keycloak Role Setup

### Required Roles

Create these realm roles manually in Keycloak (`life-control-realm`):

- **`lc-admin`** — Full CRUD on companies, activity log access (replaces `life-control-admin`)
- **`lc-company`** — Scoped company CRUD (filtered by `company_id` JWT claim)
- **`lc-company-country`** — Scoped company-country CRUD
- **`lc-company-read`** — Read-only GET access to companies, scoped by `company_id` JWT claim
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

See `AGENTS.md` (root) and `.opencode/skills/sdd-*.md` for the full SDD workflow.
