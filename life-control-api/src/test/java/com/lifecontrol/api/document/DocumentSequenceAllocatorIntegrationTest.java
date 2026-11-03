package com.lifecontrol.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0003's allocation invariants against the real schema and the real policies: N1 (no two documents of
 * one tenant, type and period share a value), N2 (allocation never reads before it writes), N3 (a tenant's
 * volume cannot move another tenant's counter), N4 (a new period starts at 1 with no reset job) and N6 (a
 * rolled-back allocation reuses its value; a committed allocation with no document leaves a gap that is
 * never reassigned).
 *
 * <p><b>Why the concurrency case is the point of the unit.</b> The defect ADR-0003 exists to remove is a
 * read followed by a write: two cashiers read the same maximum and the second insert fails inside the
 * sale's transaction. A suite that allocates sequentially proves the statement parses; only concurrent
 * callers prove the race is gone, so the case below drives several threads through the allocator and
 * asserts that the values are exactly the first {@code N}.
 *
 * <p><b>Every case runs inside a transaction, because the allocator refuses to run outside one.</b>
 * {@code Propagation.MANDATORY} is the contract that keeps N6 true: if the allocation committed on its own,
 * a failed charge would leave a gap and a retry would burn a number. {@link TenantFixtures#inTenant} is the
 * helper that opens that boundary and states the tenant, and the rollback case uses a
 * {@link TransactionTemplate} so a case can decide that its own transaction never lands.
 */
@SpringBootTest
@DisplayName("Document sequence allocation (V64, ADR-0003 decision 2)")
class DocumentSequenceAllocatorIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DocumentSequenceAllocator allocator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a new period starts at 1 and a committed allocation without a document leaves a gap")
    void startsAtOneAndNeverReassigns() {
        var company = newCompany();
        var period = freshPeriod();

        assertThat(allocate(company, period))
                .as("the first allocation of a period is an INSERT that conflicts with nothing (N4)")
                .isEqualTo(1L);
        assertThat(allocate(company, period))
                .as("a value handed out and committed is never reassigned, even with no document behind it "
                        + "(N6, second direction)")
                .isEqualTo(2L);
        assertThat(nextValue(company, period))
                .as("next_value is the NEXT value to hand out")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("a tenant's volume cannot move another tenant's counter")
    void numbersEachTenantIndependently() {
        var first = newCompany();
        var second = newCompany();
        var period = freshPeriod();

        assertThat(allocate(first, period)).isEqualTo(1L);
        assertThat(allocate(first, period)).isEqualTo(2L);
        assertThat(allocate(first, period)).isEqualTo(3L);
        assertThat(allocate(second, period))
                .as("the key is company-scoped, so the second tenant starts where every tenant starts (N3)")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a rolled-back allocation hands the same value out again")
    void reusesTheValueOfARolledBackAllocation() {
        var company = newCompany();
        var period = freshPeriod();
        var template = new TransactionTemplate(transactionManager);

        var rolledBack = TenantScope.with(
                company,
                () -> template.execute(status -> {
                    var allocated = allocator.allocate(company, DocumentType.SALES_ORDER, period);
                    status.setRollbackOnly();
                    return allocated;
                }));

        assertThat(rolledBack).isEqualTo(1L);
        assertThat(allocate(company, period))
                .as("the row lock is held until commit, so a rollback leaves next_value untouched (N6, first "
                        + "direction) and the retry does not burn a number")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("concurrent allocations of one counter never hand out the same value twice")
    void neverRepeatsUnderConcurrency() throws Exception {
        var company = newCompany();
        var period = freshPeriod();
        var threads = 8;
        var barrier = new CyclicBarrier(threads);
        var executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Long>> work = new ArrayList<>();
            for (var i = 0; i < threads; i++) {
                work.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return allocate(company, period);
                });
            }
            var allocated = new ArrayList<Long>();
            for (var future : executor.invokeAll(work, 30, TimeUnit.SECONDS)) {
                allocated.add(future.get());
            }

            assertThat(allocated)
                    .as("an upsert cannot hand the same value to two callers (N1, N2); a read-then-write would")
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.LongStream.rangeClosed(1, threads)
                            .boxed()
                            .toList());
        } finally {
            executor.shutdownNow();
        }
    }

    // ─── fixtures and helpers ──────────────────────────────────────────────────────────────────────

    /** One allocation in its own transaction, with the tenant stated the way production states it. */
    private long allocate(UUID company, String period) {
        return TenantFixtures.inTenant(company, () -> allocator.allocate(company, DocumentType.SALES_ORDER, period));
    }

    private UUID newCompany() {
        var suffix =
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return TenantFixtures.insertCompany(
                jdbcTemplate, "V64A" + suffix, "V64 Allocator " + suffix, "V64" + suffix, TenantFixtures.SEED_CURRENCY);
    }

    /**
     * A period per case, so the shared container's counters do not leak between cases: the allocation
     * contract is about {@code (company, doc_type, period)}, so a fresh period is a fresh counter without a
     * cleanup that would need a superuser.
     */
    private String freshPeriod() {
        return "T"
                + String.format(
                        "%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private Long nextValue(UUID company, String period) {
        return TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.queryForObject(
                        "SELECT next_value FROM document_sequence WHERE company_id = ? AND doc_type = ? AND period = ?",
                        Long.class,
                        company,
                        DocumentType.SALES_ORDER.code(),
                        period));
    }
}
