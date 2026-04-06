# Life Control API - Developer Guide

## Tech Stack

| Component | Technology |
|-----------|------------|
| Framework | Spring Boot 3.4.0 |
| Language | Java 21 |
| Build Tool | Gradle |
| Database | PostgreSQL |
| Port | 8082 (configurable via `SERVER_PORT`) |
| Package | `com.lifecontrol.api` |

---

## Architecture

### Layered Architecture

```
Controller → Service → Repository
```

The application follows a **Controller → Service → Repository** layered architecture:

| Layer | Responsibility | Annotation |
|-------|---------------|------------|
| **Controller** | Handle HTTP requests, validate input, return responses | `@RestController`, `@RequestMapping` |
| **Service** | Business logic, transactions, coordination | `@Service`, `@Transactional` |
| **Repository** | Data access, JPA operations | `@Repository` |

### Package Structure

Packages are organized **by domain**, not by layer:

```
com.lifecontrol.api/
├── company/           # Company domain
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── model/
│   ├── dto/
│   └── exception/
├── security/         # User management domain
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── model/
│   ├── dto/
│   └── exception/
├── config/           # Configuration
├── exception/        # Global exception handling
└── LifeControlApiApplication.java
```

---

## API Conventions

### REST Endpoints

- **Prefix**: All endpoints use `/api/` prefix
- **ID Type**: Use `UUID` for entity identifiers
- **HTTP Methods**: Follow REST conventions (GET, POST, PUT, DELETE)

### OpenAPI Documentation

Use OpenAPI annotations for API documentation:

```java
@RestController
@RequestMapping("/api/companies")
@Tag(name = "Company Management", description = "API for managing companies")
public class CompanyController {

    @GetMapping
    @Operation(summary = "Get all companies", description = "Returns a list of all companies")
    public ResponseEntity<List<CompanyResponse>> getAllCompanies() {
        return ResponseEntity.ok(companyService.getAllCompanies());
    }
}
```

### HTTP Status Codes

| Status | Usage |
|--------|-------|
| `200 OK` | Successful GET, PUT |
| `201 Created` | Successful POST |
| `204 No Content` | Successful DELETE |
| `400 Bad Request` | Validation errors |
| `404 Not Found` | Resource not found |
| `409 Conflict` | Duplicate resource (e.g., unique RFC) |

---

## Coding Patterns (NO Lombok)

**This project does NOT use Lombok.** Use modern Java features instead.

### Constructor Injection

```java
// ✅ CORRECTO - Constructor injection manual
@RestController
@RequestMapping("/api/companies")
@Tag(name = "Company Management")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }
}

// ❌ INCORRECTO - No usar @RequiredArgsConstructor
@RequiredArgsConstructor  // EVITAR
public class CompanyController { }
```

```java
// ✅ CORRECTO - Service con constructor
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }
}
```

### Records for DTOs

```java
// ✅ CORRECTO - Usar record para DTOs de respuesta
public record CompanyResponse(
    Integer companyId,
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

// ❌ INCORRECTO - No usar @Data @Builder en DTOs
@Data
@Builder
public class CompanyResponse { }  // EVITAR
```

### Entities with Manual Getters/Setters

```java
// ✅ CORRECTO - Entity con getters/setters manuales
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_key", unique = true)
    private String companyKey;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    // Getters y setters manuales
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCompanyKey() {
        return companyKey;
    }

    public void setCompanyKey(String companyKey) {
        this.companyKey = companyKey;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }
}

// Alternativa: Factory method pattern
@Entity
public class Company {

    private UUID id;
    private String companyName;

    public static Company create(String name) {
        Company company = new Company();
        company.setCompanyName(name);
        return company;
    }

    // getters/setters...
}
```

### Sealed Class for Exception Hierarchy

```java
// ✅ CORRECTO - sealed class para jerarquía de excepciones
public sealed class CompanyException extends RuntimeException
        permits CompanyNotFoundException, DuplicateCompanyException {

    protected CompanyException(String message) {
        super(message);
    }
}

public final class CompanyNotFoundException extends CompanyException {
    public CompanyNotFoundException(UUID id) {
        super("Company not found with id: " + id);
    }
}

public final class DuplicateCompanyException extends CompanyException {
    public DuplicateCompanyException(String message) {
        super(message);
    }
}
```

### Transaction Management

```java
// ✅ CORRECTO - @Transactional en métodos de service
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> getAllCompanies() {
        return companyRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        // Business logic
        Company company = Company.builder()
                .companyName(request.companyName())
                // ...
                .build();
        return toResponse(companyRepository.save(company));
    }
}
```

### Use `var` for Local Variables

```java
// ✅ CORRECTO - Usar var para inferencia de tipo local
var company = companyRepository.findById(id);
var companies = companyRepository.findAll();

// ✅ CORRECTO - for each con var
for (var company : companies) {
    // ...
}
```

---

## Testing Patterns

### Test Structure with @Nested

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyController Tests")
class CompanyControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyService companyService;

    @InjectMocks
    private CompanyController companyController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    @Nested
    @DisplayName("PUT /api/companies/{id}")
    class UpdateCompanyTests {

        @Test
        @DisplayName("updateCompany - should return 200 OK with updated company")
        void updateCompany_Success() throws Exception {
            // Arrange
            when(companyService.updateCompany(eq(testCompanyId), any(CompanyRequest.class)))
                    .thenReturn(testCompanyResponse);

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCompanyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyName").value("Test Company"));
        }
    }
}
```

### MockMvc Standalone Setup

```java
// ✅ CORRECTO - MockMvc standalone (más rápido que @SpringBootTest)
mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
```

### Service Tests

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService Tests")
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private CompanyService companyService;

    @Nested
    @DisplayName("getCompanyById")
    class GetCompanyByIdTests {

        @Test
        @DisplayName("should return company when found")
        void shouldReturnCompany_WhenFound() {
            // Arrange
            UUID id = UUID.randomUUID();
            Company company = Company.builder() // Nota: esto es del código legacy
                    .id(id)
                    .companyName("Test")
                    .build();
            when(companyRepository.findById(id)).thenReturn(Optional.of(company));

            // Act
            var result = companyService.getCompanyById(id);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Test");
        }
    }
}
```

---

## Exception Handling

### GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CompanyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyNotFound(CompanyNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        
        ex.getBindingResult().getFieldErrors().forEach(fieldError -> 
                errors.put(fieldError.getField(), fieldError.getDefaultMessage())
        );

        ValidationErrorResponse errorResponse = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                errors,
                LocalDateTime.now()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // Error response records
    public record ErrorResponse(int status, String message, LocalDateTime timestamp) {}
    public record ValidationErrorResponse(int status, String message, Map<String, String> errors, LocalDateTime timestamp) {}
}
```

### Custom Exceptions

```java
// RuntimeException base
public class CompanyException extends RuntimeException {
    public CompanyException(String message) {
        super(message);
    }
}

// Specific exceptions
public class CompanyNotFoundException extends CompanyException {
    public CompanyNotFoundException(UUID id) {
        super("Company not found with id: " + id);
    }
}

public class DuplicateCompanyException extends CompanyException {
    public DuplicateCompanyException(String message) {
        super(message);
    }
}
```

---

## Development Commands

### Build and Run

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run tests
./gradlew test

# Package as executable JAR
./gradlew bootJar
```

### Gradle Options

```bash
# Run with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Run specific test class
./gradlew test --tests "com.lifecontrol.api.company.controller.CompanyControllerTest"

# Clean and build
./gradlew clean build

# Build without tests
./gradlew build -x test
```

### Database

The application uses PostgreSQL with schema initialization:

- **Schema**: Defined in `src/main/resources/schema.sql`
- **Init Mode**: `always` (recreates tables on startup)

---

## Validation

### Bean Validation

Use Jakarta Bean Validation on DTOs:

```java
public record CompanyRequest(
    @NotNull Integer companyId,
    @NotBlank String companyName,
    @NotBlank String rfc,
    String phone,
    String email
) {}
```

```java
@PostMapping
public ResponseEntity<CompanyResponse> createCompany(
        @Valid @RequestBody CompanyRequest request) {
    // ...
}
```

---

## Observability

### Actuator

The application includes Spring Boot Actuator:

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Metrics |

### Logging

- **Framework**: SLF4J with Logback
- **Config**: `src/main/resources/logback-spring.xml`

---

## Docker

### Build and Run

```bash
# Build JAR
./gradlew bootJar

# Build Docker image
docker build -t life-control-api:latest .

# Run container
docker run -p 8082:8082 life-control-api:latest
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP port | 8082 |
| `SPRING_DATASOURCE_URL` | Database URL | jdbc:postgresql://localhost:5432/lifecontrol |
| `SPRING_DATASOURCE_USERNAME` | DB username | postgres |
| `SPRING_DATASOURCE_PASSWORD` | DB password | postgres |
