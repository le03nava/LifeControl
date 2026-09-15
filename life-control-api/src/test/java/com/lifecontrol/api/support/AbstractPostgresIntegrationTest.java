package com.lifecontrol.api.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need real PostgreSQL semantics.
 * <p>
 * Starts a single {@link PostgreSQLContainer} per JVM (singleton container) and
 * points Spring's datasource at it, enabling Flyway so the schema is built by the
 * exact same migrations used in production ({@code V1} to {@code V3}). This removes
 * the H2/PostgreSQL dialect divergences that made H2 an unreliable proxy for
 * production column types and constraints.
 * <p>
 * Test classes extending this base must still declare {@code @SpringBootTest}
 * (and {@code @AutoConfigureMockMvc} when needed). Pure slices ({@code @WebMvcTest}),
 * unit tests, and AOP/cache tests backed by mocked repositories stay on H2.
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lifecontrol_test")
            .withUsername("lifecontrol")
            .withPassword("lifecontrol");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
