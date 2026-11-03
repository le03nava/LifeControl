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
