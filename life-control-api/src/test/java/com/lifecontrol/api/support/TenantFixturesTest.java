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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Pins the contract the fixtures depend on: the id exists <b>before</b> the insert, the save is stated with
 * it, and the write <b>owns the transaction</b> whose boundary installs it, so a fixture cannot insert a row
 * without a tenant once the pool connects as {@code lifecontrol_app} (records T6a and T6b-2).
 */
class TenantFixturesTest {

    /**
     * The one fact the real {@code TenantScopeJpaTransactionManager} needs: what the thread's scope was
     * <b>when the transaction began</b>. Everything else about a boundary is Spring's.
     */
    private record Boundary(int propagation, TenantScope.Scope scopeAtBegin) {}

    /**
     * A boundary that records instead of opening one. {@code getTransaction} is where the real manager
     * installs {@code set_config}, so the scope read there is the whole contract this suite pins.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<Boundary> boundaries = new ArrayList<>();
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            boundaries.add(new Boundary(
                    definition.getPropagationBehavior(), TenantScope.current().orElse(null)));
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }

    private final RecordingTransactionManager transactions = new RecordingTransactionManager();

    @BeforeEach
    void bindBoundary() {
        TenantFixtures.bindTransactionManager(transactions);
    }

    @AfterEach
    void restoreTheThread() {
        TenantFixtures.unbindTransactionManager();
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

    // ─── the boundary: the fixture owns the transaction its scope is installed in (record T6b-2) ────

    @Test
    @DisplayName("a repository write opens no second boundary: Spring Data's own transaction is the boundary")
    void aRepositoryWriteOpensNoSecondBoundary() {
        var repository = mock(CompanyRepository.class);
        when(repository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0, Company.class));

        var company = TenantFixtures.persist(
                repository, Company.builder().companyKey("FIXTURE").build());

        assertThat(transactions.boundaries)
                .as("the repository's @Transactional already runs doBegin, and it sees the scope stated before "
                        + "the call; a REQUIRES_NEW wrapper would commit the row past a suite's rollback to add "
                        + "nothing")
                .isEmpty();
        assertThat(TenantScope.current())
                .as("the scope is stated around the save and restored afterwards")
                .isEmpty();
        assertThat(company.getId()).isNotNull();
    }

    @Test
    @DisplayName("the raw INSERT runs inside the boundary, so the JdbcTemplate meets the installed scope")
    void theRawInsertRunsInsideTheBoundary() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var scopeAtInsert = new AtomicReference<TenantScope.Scope>();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
            scopeAtInsert.set(TenantScope.current().orElse(null));
            return 1;
        });

        var id = TenantFixtures.insertCompany(jdbcTemplate, "RAW-KEY", "Raw Fixture", "RAWX010101AB");

        assertThat(transactions.boundaries)
                .as("a plain jdbcTemplate.update draws its own pooled connection and opens no transaction, so "
                        + "the boundary is the only thing that makes the statement carry the scope")
                .containsExactly(
                        new Boundary(TransactionDefinition.PROPAGATION_REQUIRES_NEW, new TenantScope.Scope(id, null)));
        assertThat(scopeAtInsert.get()).isEqualTo(new TenantScope.Scope(id, null));
    }

    @Test
    @DisplayName("findOrCreate leaves the boundary to the repository, and states the derived scope around both")
    void findOrCreateLeavesTheBoundaryToTheRepository() {
        var repository = mock(CompanyRepository.class);
        var scopeAtRead = new AtomicReference<TenantScope.Scope>();
        when(repository.findByCompanyKey(anyString())).thenAnswer(invocation -> {
            scopeAtRead.set(TenantScope.current().orElse(null));
            return Optional.empty();
        });
        when(repository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0, Company.class));

        TenantFixtures.findOrCreate(repository, "TEST-COMPANY", "Test Company", "TEST010101ABC");

        var derived = TenantFixtures.derivedId("TEST-COMPANY");
        assertThat(scopeAtRead.get()).isEqualTo(new TenantScope.Scope(derived, null));
        assertThat(transactions.boundaries)
                .as("the read's and the save's transactions are Spring Data's, and both begin inside the "
                        + "scope stated here")
                .isEmpty();
    }

    @Test
    @DisplayName("inTenant restores the caller's scope, so a nested fixture does not leak its tenant")
    void inTenantRestoresThePreviousScope() {
        var outer = UUID.randomUUID();
        var inner = UUID.randomUUID();

        TenantScope.with(
                outer,
                () -> TenantFixtures.inTenant(inner, () -> {
                    assertThat(TenantScope.current())
                            .as("the work runs inside the tenant it asked for")
                            .contains(new TenantScope.Scope(inner, null));
                    return null;
                }));

        assertThat(transactions.boundaries)
                .as("the inner boundary states the inner tenant, not the outer one")
                .containsExactly(new Boundary(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW, new TenantScope.Scope(inner, null)));
        assertThat(TenantScope.current())
                .as("and the outer scope survives the inner call")
                .isEmpty();
    }

    @Test
    @DisplayName("with no boundary bound, a raw write refuses instead of inserting with no scope installed")
    void aWriteWithNoBoundaryRefusesLoudly() {
        TenantFixtures.unbindTransactionManager();

        assertThatThrownBy(() -> TenantFixtures.insertCompany(
                        mock(JdbcTemplate.class), "RAW-KEY", "Raw Fixture", "RAWX010101AB"))
                .as("a silent fallback would insert with no scope declared and fail later with the policy's "
                        + "message instead of this one")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AbstractPostgresIntegrationTest");
    }

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
