package com.lifecontrol.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the contract the fixtures depend on: the id exists <b>before</b> the insert and the save is stated
 * with it, so a fixture cannot insert a company without a tenant once the pool connects as
 * {@code lifecontrol_app} (record T6a).
 */
class TenantFixturesTest {

    @AfterEach
    void clearScope() {
        TenantScope.clear();
    }

    @Test
    @DisplayName("persist assigns the id first and states it around the save")
    void persistAssignsTheIdFirstAndStatesIt() {
        var repository = mock(CompanyRepository.class);
        var scopeAtSave = new AtomicReference<TenantScope.Scope>();
        when(repository.save(any(Company.class))).thenAnswer(invocation -> {
            scopeAtSave.set(TenantScope.current().orElse(null));
            return invocation.getArgument(0, Company.class);
        });

        var company = TenantFixtures.persist(
                repository, Company.builder().companyKey("FIXTURE").build());

        assertThat(company.getId())
                .as("the id is assigned before the save, so the scope can carry it")
                .isNotNull();
        assertThat(scopeAtSave.get())
                .as("the save runs inside the tenant scope of the row it inserts")
                .isEqualTo(new TenantScope.Scope(company.getId(), null));
        assertThat(TenantScope.current())
                .as("the helper restores the thread's previous scope instead of leaking one")
                .isEmpty();
    }

    @Test
    @DisplayName("persist keeps the id the caller stated")
    void persistKeepsTheCallersId() {
        var repository = mock(CompanyRepository.class);
        var stated = UUID.randomUUID();
        var scopeAtSave = new AtomicReference<TenantScope.Scope>();
        when(repository.save(any(Company.class))).thenAnswer(invocation -> {
            scopeAtSave.set(TenantScope.current().orElse(null));
            return invocation.getArgument(0, Company.class);
        });

        var company = TenantFixtures.persist(
                repository, Company.builder().id(stated).companyKey("FIXTURE").build());

        assertThat(company.getId()).isEqualTo(stated);
        assertThat(scopeAtSave.get()).isEqualTo(new TenantScope.Scope(stated, null));
    }

    @Test
    @DisplayName("insertCompany states the derived id around the raw insert")
    void insertCompanyStatesTheGeneratedId() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var scopeAtInsert = new AtomicReference<TenantScope.Scope>();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
            scopeAtInsert.set(TenantScope.current().orElse(null));
            return 1;
        });

        var id = TenantFixtures.insertCompany(jdbcTemplate, "RAW-KEY", "Raw Fixture", "RAWX010101AB");

        assertThat(id)
                .as("a raw fixture of a key lands on the same id a repository seed of that key derives")
                .isEqualTo(TenantFixtures.derivedId("RAW-KEY"));
        assertThat(scopeAtInsert.get())
                .as("the raw INSERT runs inside the tenant scope of the row it inserts")
                .isEqualTo(new TenantScope.Scope(id, null));
        assertThat(TenantScope.current()).isEmpty();
    }

    // ─── the derived id: the shape the 376 duplicate-key seeds need (record L25) ────────────

    @Test
    @DisplayName("derivedId is a pure function of the key, pinned to nameUUIDFromBytes")
    void derivedIdIsPureAndPinned() {
        assertThat(TenantFixtures.derivedId("TEST-COMPANY"))
                .as("a pinned value: changing the derivation changes the id of every seeded company")
                .isEqualTo(UUID.fromString("a3f19b7b-0de8-3a20-9d37-8ce3acd120c9"));
        assertThat(TenantFixtures.derivedId("TEST-COMPANY"))
                .as("two seeds of one key agree, in any class and in any run")
                .isEqualTo(TenantFixtures.derivedId("TEST-COMPANY"));
        assertThat(TenantFixtures.derivedId("TEST-COMPANY"))
                .as("two different keys are two different companies")
                .isNotEqualTo(TenantFixtures.derivedId("TEST-COMPANY-2"));
    }

    @Test
    @DisplayName("derivedId refuses a missing key instead of deriving from null")
    void derivedIdRefusesANullKey() {
        assertThatThrownBy(() -> TenantFixtures.derivedId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("natural key");
    }

    @Test
    @DisplayName("findOrCreate states the derived id BEFORE the read and inserts at that id")
    void findOrCreateStatesTheDerivedIdBeforeTheRead() {
        var repository = mock(CompanyRepository.class);
        var scopeAtRead = new AtomicReference<TenantScope.Scope>();
        var scopeAtSave = new AtomicReference<TenantScope.Scope>();
        when(repository.findByCompanyKey(anyString())).thenAnswer(invocation -> {
            scopeAtRead.set(TenantScope.current().orElse(null));
            return Optional.empty();
        });
        when(repository.save(any(Company.class))).thenAnswer(invocation -> {
            scopeAtSave.set(TenantScope.current().orElse(null));
            return invocation.getArgument(0, Company.class);
        });

        var company = TenantFixtures.findOrCreate(repository, "TEST-COMPANY", "Test Company", "TEST010101ABC");

        var derived = TenantFixtures.derivedId("TEST-COMPANY");
        assertThat(scopeAtRead.get())
                .as("the read states the derived id, so the policy answers with the row a seed would insert")
                .isEqualTo(new TenantScope.Scope(derived, null));
        assertThat(scopeAtSave.get())
                .as("the insert runs in the same scope, which is what the WITH CHECK of the policy needs")
                .isEqualTo(new TenantScope.Scope(derived, null));
        assertThat(company.getId()).isEqualTo(derived);
        assertThat(company.getCompanyKey()).isEqualTo("TEST-COMPANY");
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    @DisplayName("findOrCreate reuses the row the read found instead of inserting a second one")
    void findOrCreateReusesTheRowItFound() {
        var repository = mock(CompanyRepository.class);
        var existing = Company.builder()
                .id(TenantFixtures.derivedId("TEST-COMPANY"))
                .companyKey("TEST-COMPANY")
                .companyName("Seeded By Another Class")
                .rfc("TEST010101ABC")
                .build();
        when(repository.findByCompanyKey("TEST-COMPANY")).thenReturn(Optional.of(existing));

        var company = TenantFixtures.findOrCreate(repository, "TEST-COMPANY", "Test Company", "TEST010101ABC");

        assertThat(company)
                .as("the seed returns the existing row, which is what made the duplicate key unreachable")
                .isSameAs(existing);
        verify(repository, never()).save(any(Company.class));
    }

    @Test
    @DisplayName("findOrCreate(prototype) derives from the prototype's own key and keeps its other columns")
    void findOrCreateKeepsThePrototypeColumns() {
        var repository = mock(CompanyRepository.class);
        when(repository.findByCompanyKey("TEST-COMPANY")).thenAnswer(invocation -> Optional.empty());
        when(repository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0, Company.class));

        var company = TenantFixtures.findOrCreate(
                repository,
                Company.builder()
                        .id(UUID.randomUUID())
                        .companyKey("TEST-COMPANY")
                        .companyName("Test Company")
                        .rfc("TEST010101ABC")
                        .emailDomain("test.example")
                        .build());

        assertThat(company.getId())
                .as("a caller's random id is replaced by the key's derivation, never kept")
                .isEqualTo(TenantFixtures.derivedId("TEST-COMPANY"));
        assertThat(company.getEmailDomain()).isEqualTo("test.example");
    }

    @Test
    @DisplayName("findOrCreateAndFlush flushes the insert at the derived id")
    void findOrCreateAndFlushFlushesAtTheDerivedId() {
        var repository = mock(CompanyRepository.class);
        var scopeAtSave = new AtomicReference<TenantScope.Scope>();
        when(repository.findByCompanyKey("TEST-COMPANY")).thenAnswer(invocation -> Optional.empty());
        when(repository.saveAndFlush(any(Company.class))).thenAnswer(invocation -> {
            scopeAtSave.set(TenantScope.current().orElse(null));
            return invocation.getArgument(0, Company.class);
        });

        var company = TenantFixtures.findOrCreateAndFlush(repository, "TEST-COMPANY", "Test Company", "TEST010101ABC");

        assertThat(company.getId()).isEqualTo(TenantFixtures.derivedId("TEST-COMPANY"));
        assertThat(scopeAtSave.get()).isEqualTo(new TenantScope.Scope(TenantFixtures.derivedId("TEST-COMPANY"), null));
    }
}
