package com.lifecontrol.api.salesorder.repository;

import com.lifecontrol.api.salesorder.model.SalePayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Reads and appends the payments of a sales order.
 *
 * <p>The table is append-only by trigger and by grant, so this repository has no update and no delete of its
 * own: {@code save} is an insert, and a void is a row in {@code sale_payment_void} that the void slice
 * appends.</p>
 */
@Repository
public interface SalePaymentRepository extends JpaRepository<SalePayment, UUID> {

    List<SalePayment> findBySalesOrderIdOrderByPaidAtAsc(UUID salesOrderId);

    /**
     * What the document has actually been paid: {@code Σ CHARGE − Σ REFUND} over its **non-voided** rows,
     * which is ADR-0002 invariant M3 stated once, in the one place a sum of rows can be stated.
     *
     * <p><b>Why SQL and not a stream over {@link #findBySalesOrderIdOrderByPaidAtAsc}.</b> The voided rows
     * have to be excluded by their own table, and reading every payment into memory to exclude them is how
     * the guard and the report end up disagreeing about which rows count. The {@code NOT EXISTS} names the
     * rule: a payment is void if and only if a void row names it.</p>
     *
     * <p>The comparison that uses this number is the service's, against the document's own
     * {@code total_amount}: a cross-row sum is not expressible as a {@code CHECK}, so the invariant needs a
     * caller that holds the order's row lock (M10) — not a wish in a comment.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN p.direction = 'CHARGE' THEN p.amount ELSE -p.amount END), 0)
            FROM sale_payment p
            WHERE p.company_id = :companyId
              AND p.sales_order_id = :salesOrderId
              AND NOT EXISTS (
                  SELECT 1 FROM sale_payment_void v
                  WHERE v.payment_id = p.id AND v.company_id = p.company_id
              )
            """, nativeQuery = true)
    BigDecimal netPaid(@Param("companyId") UUID companyId, @Param("salesOrderId") UUID salesOrderId);
}
