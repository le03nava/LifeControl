package com.lifecontrol.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
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
    @DisplayName("insertCompany states the generated id around the raw insert")
    void insertCompanyStatesTheGeneratedId() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var scopeAtInsert = new AtomicReference<TenantScope.Scope>();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
            scopeAtInsert.set(TenantScope.current().orElse(null));
            return 1;
        });

        var id = TenantFixtures.insertCompany(jdbcTemplate, "RAW-KEY", "Raw Fixture", "RAWX010101AB");

        assertThat(id).isNotNull();
        assertThat(scopeAtInsert.get())
                .as("the raw INSERT runs inside the tenant scope of the row it inserts")
                .isEqualTo(new TenantScope.Scope(id, null));
        assertThat(TenantScope.current()).isEmpty();
    }
}
