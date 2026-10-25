package com.lifecontrol.api.purchaseorder.repository;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findByEnabledTrue();

    Page<PurchaseOrder> findByEnabledTrue(Pageable pageable);

    @Override
    @EntityGraph(value = "PurchaseOrder.withHierarchy", type = EntityGraph.EntityGraphType.FETCH)
    Optional<PurchaseOrder> findById(UUID id);

    @EntityGraph(value = "PurchaseOrder.withHierarchy", type = EntityGraph.EntityGraphType.FETCH)
    Page<PurchaseOrder> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The paginated purchase-order list with the two optional filters the module exposes: the
     * free-text {@code search} and the five ids of the store hierarchy.
     *
     * <p>It is one query with optional predicates instead of a searched and an unsearched finder,
     * because since record S4 the list always filters by the same set of levels and only the bound
     * values vary. Every predicate combines with {@code AND}: the five location ids among themselves,
     * the {@code search} term with them, and all of them with the {@code enabled = true} the module
     * list already applied. <b>A missing filter is no constraint</b> — a null {@code search} and five
     * null ids return exactly the page {@code findByEnabledTrueOrderByCreatedAtDesc} returns, in the
     * same {@code createdAt DESC} order.
     *
     * <p><b>Every occurrence of {@code :search} is cast, and removing any of them is a regression.</b>
     * This stack cannot infer the type of a null {@code String} parameter, and a Hibernate parameter
     * carries one type per binding site: typing the {@code IS NULL} branch alone leaves the two
     * {@code LIKE} bindings untyped. Measured on PostgreSQL 16 with the {@code search} parameter absent
     * (see {@code PurchaseOrderListNullSearchIntegrationTest}): the rendered predicate became
     * {@code lower(s.supplier_name) like lower(('%'||?||'%'))}, PostgreSQL resolved the untyped
     * {@code ('%'||?||'%')} concatenation as {@code bytea}, and the request answered
     * <b>HTTP 500</b> with {@code ERROR: function lower(bytea) does not exist}. The cast inside
     * {@code CONCAT} is what types the {@code LIKE} occurrences; the separate cast in
     * {@code cast(:search as string) IS NULL} is what types the null guard. The five UUID levels need no
     * such cast: each one is also compared against a typed path ({@code c.id = :companyId}, &hellip;), and
     * that comparison is what lets the null binding be typed.
     *
     * <p>The {@code search} still matches {@code supplier.supplierName} and
     * {@code companyStore.storeName} only, case-insensitively and with {@code LIKE '%…%'}; the order
     * number has never been part of it (S5), and this query does not add it.
     *
     * <p>The fetch joins initialize the response's whole hierarchy, so the mapper never triggers a
     * lazy load per row. They are on to-one associations, so they do not multiply the rows and the
     * page slice stays the database's. The explicit {@code countQuery} repeats the predicates without
     * the fetch joins, which a count query cannot carry.
     */
    @Query(value = """
        SELECT po FROM PurchaseOrder po
        LEFT JOIN FETCH po.supplier
        LEFT JOIN FETCH po.companyStore cs
        LEFT JOIN FETCH cs.companyZone cz
        LEFT JOIN FETCH cz.companyRegion cr
        LEFT JOIN FETCH cr.companyCountry cc
        LEFT JOIN FETCH cc.company c
        LEFT JOIN FETCH po.paymentMethod
        LEFT JOIN FETCH po.status
        WHERE po.enabled = true
          AND (cast(:search as string) IS NULL
             OR LOWER(po.supplier.supplierName) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
             OR LOWER(po.companyStore.storeName) LIKE LOWER(CONCAT('%', cast(:search as string), '%')))
          AND (:companyId IS NULL OR c.id = :companyId)
          AND (:companyCountryId IS NULL OR cc.id = :companyCountryId)
          AND (:regionId IS NULL OR cr.id = :regionId)
          AND (:zoneId IS NULL OR cz.id = :zoneId)
          AND (:companyStoreId IS NULL OR cs.id = :companyStoreId)
        ORDER BY po.createdAt DESC
        """, countQuery = """
        SELECT COUNT(po) FROM PurchaseOrder po
        LEFT JOIN po.companyStore cs
        LEFT JOIN cs.companyZone cz
        LEFT JOIN cz.companyRegion cr
        LEFT JOIN cr.companyCountry cc
        LEFT JOIN cc.company c
        WHERE po.enabled = true
          AND (cast(:search as string) IS NULL
             OR LOWER(po.supplier.supplierName) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
             OR LOWER(po.companyStore.storeName) LIKE LOWER(CONCAT('%', cast(:search as string), '%')))
          AND (:companyId IS NULL OR c.id = :companyId)
          AND (:companyCountryId IS NULL OR cc.id = :companyCountryId)
          AND (:regionId IS NULL OR cr.id = :regionId)
          AND (:zoneId IS NULL OR cz.id = :zoneId)
          AND (:companyStoreId IS NULL OR cs.id = :companyStoreId)
        """)
    Page<PurchaseOrder> findByFilters(
            @Param("search") String search,
            @Param("companyId") UUID companyId,
            @Param("companyCountryId") UUID companyCountryId,
            @Param("regionId") UUID regionId,
            @Param("zoneId") UUID zoneId,
            @Param("companyStoreId") UUID companyStoreId,
            Pageable pageable);

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    Optional<PurchaseOrder> findTopByOrderNumberStartingWithOrderByOrderNumberDesc(String orderNumberPrefix);

    /**
     * Pessimistic write lock on the purchase order. The goods receipt takes this lock before it
     * numbers the receipt and applies the reception, so two concurrent receptions against the same
     * order queue instead of racing the per-order receipt counter. It is the first lock of the
     * documented {@code purchaseOrder -> storeStock -> locationBalance} order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);
}
