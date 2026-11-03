package com.lifecontrol.api.common.document;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single allocator of ADR-0003 decision 2: one statement, no read followed by a write.
 *
 * <p>{@code document_sequence} holds one row per {@code (company_id, doc_type, period)} and
 * {@code next_value} is the next value to hand out, so the first allocation of a period inserts {@code 2}
 * and hands out {@code 1}. The upsert is atomic, which is what removes the race instead of retrying it: two
 * cashiers of one tenant can no longer read the same maximum.</p>
 *
 * <p><strong>It runs inside the caller's transaction, and that is a requirement, not a convenience.</strong>
 * The row lock the upsert takes is held until the caller commits, which is what makes a rolled-back
 * allocation hand its value out again (invariant N6): if this method committed on its own, a failed charge
 * would leave a gap and a retry would burn a number. {@link Propagation#MANDATORY} states the contract so a
 * future caller that drops the transaction fails loudly at the call instead of silently changing numbering
 * semantics.</p>
 *
 * <p><strong>Two costs the callers own.</strong> First, the lock serializes every allocation of one tenant,
 * one type and one period for the rest of that transaction, so the caller allocates as its <em>last</em>
 * write before commit. Second, a transaction that allocates more than one type allocates them in a fixed
 * order ({@code doc_type} ascending, then {@code period}) so two such transactions cannot deadlock on two
 * sequence rows.</p>
 *
 * <p>{@code document_sequence} is a tenant table armed by ADR-0005's machinery, so the caller's transaction
 * must have stated the same company the allocation names: the policy's {@code WITH CHECK} refuses any other,
 * which is exactly the guarantee that a counter row belongs to its tenant (invariant N9).</p>
 */
@Component
public class DocumentSequenceAllocator {

    /**
     * ADR-0003 decision 2 verbatim, plus {@code updated_at}. The added clause is the only deviation and it
     * changes no semantics: allocation is still the {@code next_value} increment the ADR wrote, and a column
     * that exists to record the last write and never records one is a lie the schema does not need.
     */
    private static final String ALLOCATE_SQL = """
            INSERT INTO document_sequence (company_id, doc_type, period, next_value)
            VALUES (?, ?, ?, 2)
            ON CONFLICT (company_id, doc_type, period)
            DO UPDATE SET next_value = document_sequence.next_value + 1, updated_at = now()
            RETURNING next_value - 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public DocumentSequenceAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Hands out the next value of one tenant's counter for one type and one period.
     *
     * @param companyId the tenant that owns the counter row
     * @param type a period-numbered document type; a derived type is refused
     * @param period the document's own period, frozen before this call
     * @return the allocated value, starting at {@code 1} for a new period
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long allocate(UUID companyId, DocumentType type, String period) {
        if (companyId == null) {
            throw new IllegalArgumentException("A document sequence row belongs to a tenant, and none was given");
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "A document sequence row belongs to a document type, and none was given");
        }
        if (!type.periodNumbered()) {
            throw new IllegalArgumentException("Document type " + type.code()
                    + " is derived and must not open a counter: its number comes from its parent");
        }
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("A document sequence row belongs to a period, and none was given");
        }
        DocumentNumberRenderer.requireValidPeriod(period);

        var allocated = jdbcTemplate.queryForObject(ALLOCATE_SQL, Long.class, companyId, type.code(), period);
        if (allocated == null) {
            throw new IllegalStateException("The allocator returned no value for " + type.code() + "/" + period);
        }
        return allocated;
    }
}
