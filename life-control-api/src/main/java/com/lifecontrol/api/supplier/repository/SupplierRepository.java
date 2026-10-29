package com.lifecontrol.api.supplier.repository;

import com.lifecontrol.api.supplier.model.Supplier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Supplier persistence.
 *
 * <p>Every read the API serves is <b>company-scoped</b>: the company is part of the path
 * ({@code /api/companies/{companyId}/suppliers}), so the finders carry it instead of letting the caller
 * filter afterwards, and a page can never be post-filtered into a lie about its own totals. The
 * unscoped finders that fed the previous global catalog are gone rather than left unused, because one
 * of them being picked up later is exactly how a cross-tenant read would come back — the same rule
 * {@link com.lifecontrol.api.product.repository.ProductRepository} states.</p>
 *
 * <p>{@link #findByRfc(String)} is the single exception and it is not part of the API: a goods-receipt
 * fixture outside this module finds-or-creates its supplier by RFC and writes its purchase orders
 * straight through the repositories, so it never passes through the scope this class now enforces. Its
 * only caller is that fixture, and it goes when the fixture seeds the company instead.</p>
 *
 * <p>The RFC key is per company ({@code (company_id, rfc)}): two separate businesses may legitimately
 * register the same RFC, which is why both uniqueness finders take the company. The write path enforces
 * that shape in the service; the database enforces it from {@code V42}, which replaces V1's global key
 * — the per-company check cannot be narrower than the constraint behind it, so until that migration the
 * global key still refuses a second company's row.</p>
 *
 * <p>Every finder that feeds a response carries the graph {@code {"company"}}: the response reports
 * {@code companyId} and the relation is {@code LAZY}, so without the graph a page of twelve suppliers
 * would issue twelve extra selects for a column the query already joined.</p>
 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    /** Fixture-only, unscoped, and never called by the API: see the type javadoc. */
    Optional<Supplier> findByRfc(String rfc);

    /** Whether the company already holds this RFC. */
    boolean existsByCompanyIdAndRfc(UUID companyId, String rfc);

    /** Whether another supplier of the same company already holds this RFC. */
    boolean existsByCompanyIdAndRfcAndIdNot(UUID companyId, String rfc, UUID id);

    /**
     * A supplier of the company, or empty — a row of another company is indistinguishable from none.
     *
     * <p>Deliberately scoped instead of a load-then-compare: the foreign row is never materialized, so
     * the answer cannot drift from the scope the query used.</p>
     */
    @EntityGraph(attributePaths = {"company"})
    Optional<Supplier> findByIdAndCompanyId(UUID id, UUID companyId);

    @EntityGraph(attributePaths = {"company"})
    Page<Supplier> findByCompanyIdAndEnabledTrue(UUID companyId, Pageable pageable);

    @EntityGraph(attributePaths = {"company"})
    Page<Supplier> findByCompanyId(UUID companyId, Pageable pageable);

    @EntityGraph(attributePaths = {"company"})
    @Query("""
            SELECT s FROM Supplier s
            WHERE s.company.id = :companyId
              AND (LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.rfc) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.razonSocial) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.email) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Supplier> findByCompanyIdAndSearchTerm(
            @Param("companyId") UUID companyId, @Param("search") String search, Pageable pageable);

    @EntityGraph(attributePaths = {"company"})
    @Query("""
            SELECT s FROM Supplier s
            WHERE s.company.id = :companyId
              AND (LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.rfc) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.razonSocial) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.email) LIKE LOWER(CONCAT('%', :search, '%')))
              AND s.enabled = true
            """)
    Page<Supplier> findByCompanyIdAndSearchTermAndEnabledTrue(
            @Param("companyId") UUID companyId, @Param("search") String search, Pageable pageable);
}
