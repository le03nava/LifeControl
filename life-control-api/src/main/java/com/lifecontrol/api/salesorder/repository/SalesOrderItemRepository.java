package com.lifecontrol.api.salesorder.repository;

import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, UUID> {

    List<SalesOrderItem> findBySalesOrderId(UUID salesOrderId);

    List<SalesOrderItem> findBySalesOrderIdAndEnabledTrue(UUID salesOrderId);

    List<SalesOrderItem> findByProductVariantId(UUID productVariantId);

    /**
     * The three parts of a document's money, summed over the lines it still holds: ADR-0002 invariant M2 read
     * from the rows instead of from memory.
     *
     * <p><b>Why the database answers and the service does not add up its own list.</b> The line's
     * {@code subtotal} is derived by {@code trg_sales_order_items_subtotal} on the way in, so a line saved a
     * statement ago already carries a value the persistence context never saw — summing the entities would add
     * the {@code null} the trigger is about to replace with a real number, and the total would be wrong in the
     * one transaction that matters. A query is executed inside the same transaction, so it reads exactly the
     * rows the writer just produced, and the line's money keeps one definition: the schema's.</p>
     *
     * <p>It returns one row of three nullable columns: {@code null} when the document holds no enabled line,
     * which the caller reads as zero. Hibernate flushes the pending line writes before the query runs, which is
     * what makes the sum include the write that triggered it (M2: "recomputed in the same transaction as the
     * line write").</p>
     *
     * @param salesOrderId the document whose enabled lines are summed
     * @return a single row of {@code [subtotal, discount_total, tax_total]}
     */
    @Query("""
        SELECT SUM(i.subtotal), SUM(i.discountApplied), SUM(i.taxAmount)
        FROM SalesOrderItem i
        WHERE i.salesOrderId = :salesOrderId
          AND i.enabled = true
        """)
    List<Object[]> sumMoneyParts(@Param("salesOrderId") UUID salesOrderId);

    /**
     * Takes the line's next write sequence: one statement, the row lock, and the new value back.
     *
     * <p><b>Why this is SQL and not a read-modify-write on the entity</b> ({@code V73}, and {@code
     * odd/tasks/ledger-per-write-identity.md} S3). Two concurrent edits of one line would both read the same
     * counter, both book under it, and the narrower of the two facts would be lost: either the second movement is
     * refused by {@code uq_movement_source} — turning a legal concurrent edit into a 500 — or a duplicate slips
     * through a key that has become decoration. {@code UPDATE … RETURNING} is one statement, so the second writer
     * waits on the first's row lock and re-reads under {@code READ COMMITTED}: the numbers cannot collide. The
     * column is deliberately not a field of {@link SalesOrderItem}, so Hibernate's flush has no stale value to
     * write back over the one SQL just produced.</p>
     *
     * <p>It joins the caller's transaction ({@code @Transactional} here is {@code REQUIRED}), which is what makes
     * the bump and the movement it numbers one fact: a rolled-back edit hands its number out again, and a line
     * whose booking fails is left with the counter it had.</p>
     *
     * @param id the line whose write is starting
     * @return the value the line now holds, starting at {@code 1} for its first write; a caller that books a
     *     movement for a line that is not there reaches the empty result instead, because inventing a sequence
     *     would hide the defect
     */
    @Transactional
    @Query(
            value = "UPDATE sales_order_items SET write_seq = write_seq + 1 WHERE id = :id RETURNING write_seq",
            nativeQuery = true)
    long bumpWriteSeq(@Param("id") UUID id);
}
