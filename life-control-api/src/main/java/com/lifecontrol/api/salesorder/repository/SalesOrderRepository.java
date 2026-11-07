package com.lifecontrol.api.salesorder.repository;

import com.lifecontrol.api.salesorder.model.SalesOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    /**
     * The order row taken with {@code SELECT … FOR UPDATE}, which is ADR-0002 invariant M10 and the lock the
     * whole charge transaction serializes on.
     *
     * <p><b>What it is for.</b> Every write that changes the document's money — the charge, and each line
     * write that recomputes the breakdown — has to read the sibling rows, add them up and write the result
     * back, and a cross-row sum is not expressible as a {@code CHECK}. Without the lock two concurrent
     * writes each compute a breakdown from the state before the other's, and the second one to commit wins
     * with a total that never matched its own lines. Two charges of the same order are the sharpest case: the
     * retry serializes behind the commit, sees {@code COMPLETED} and the ADR-0004 guard refuses it, instead
     * of inserting a second payment.</p>
     *
     * <p><b>Why a named query and not {@code findById} plus a lock hint at the call site.</b> A caller that
     * loads without the lock and later decides to want one has already read the row it is about to protect,
     * and the read is what goes stale. The lock belongs to the statement that reads.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT so FROM SalesOrder so WHERE so.id = :id")
    Optional<SalesOrder> findByIdForUpdate(@Param("id") UUID id);

    Optional<SalesOrder> findByOrderNumber(String orderNumber);

    List<SalesOrder> findByCustomerId(UUID customerId);

    List<SalesOrder> findByShiftId(UUID shiftId);

    Page<SalesOrder> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT so FROM SalesOrder so
        WHERE so.enabled = true
          AND LOWER(so.orderNumber) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY so.createdAt DESC
        """)
    Page<SalesOrder> findBySearchTerm(@Param("search") String search, Pageable pageable);

    /**
     * How many decimals the document's own currency is rounded to (ADR-0002 decision 1: the currency decides
     * the rounding, and it happens once at the document boundary).
     *
     * <p><b>Why the query starts at the order and not at the company.</b> The number belongs to the
     * {@code currency} catalog and the document points at it through {@code sales_orders.currency}, which is
     * the value a trigger derives from the owning company on the way in. Reading it here keeps the catalog out
     * of this service's constructor, and it reads the same column the document will be compared against: a
     * caller that resolved the currency from the company instead would state a different source than the row
     * it is about to write. It is SQL because the join crosses two schemas' concerns — the tenant table and
     * the platform catalog — and no entity in this package maps {@code currency}.</p>
     *
     * <p>Empty means the document names a currency the catalog does not hold, which the caller must refuse
     * rather than paper over with a default: a silent scale is the failure class this whole ADR removes.</p>
     */
    @Query(value = """
            SELECT cu.minor_units
            FROM sales_orders so
            JOIN currency cu ON cu.code = so.currency
            WHERE so.id = :id
            """, nativeQuery = true)
    Optional<Integer> findCurrencyMinorUnits(@Param("id") UUID id);

    /**
     * The currency code the document itself states (ADR-0002 decision 2), which is what the response reports.
     *
     * <p><b>Why the row and not the company.</b> {@code V80}'s trigger derives {@code sales_orders.currency} from
     * the owning company on the way in and refuses a document that states another code, so the column and the
     * company agree by construction — but the document is the thing being answered, and a caller that resolved
     * the code from the company instead would state a second source that happens to agree today. The same reading
     * {@link #findCurrencyMinorUnits} takes, one column over, and the response needs both: the code to print and
     * the scale the document was rounded to.</p>
     *
     * <p><b>Why this is SQL and not a getter.</b> No entity in this package maps {@code currency} —
     * {@link SalesOrder} deliberately does not, because the column has one writer (the trigger) and mapping it
     * would make it a field something could set. A JPQL query cannot read a column no entity maps, so the
     * narrowest statement that answers the question is the native one below: it joins nothing, because the code
     * is on the row the caller already named.</p>
     *
     * <p>Empty means the row is not there for this caller — the tenant's policy hides another company's document
     * exactly as it hides a deleted one. An order this service answers has already been loaded through the same
     * policy, so an empty answer here is a broken read and the caller must say so instead of skipping the
     * field: a response that omits the currency is the defect this query exists to close.</p>
     */
    @Query(value = """
            SELECT so.currency
            FROM sales_orders so
            WHERE so.id = :id
            """, nativeQuery = true)
    Optional<String> findCurrencyCode(@Param("id") UUID id);
}
