package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.model.ProductVariant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes of the GLOBAL variant definition.
 *
 * <p>Every store-scoped concern moved to {@code ProductVariantStoreStockRepository}: this interface
 * no longer carries {@code company_store_id}, so the derived finders that filtered by store were
 * replaced by explicit {@code @Query} joins onto {@code product_variant_store_stock}. The
 * pessimistic lock on a variant also moved, because the serialization point for stock and pricing is
 * now the per-store row ({@code ProductVariantStoreStockRepository#findByProductVariantIdAndCompanyStoreIdForUpdate}).</p>
 */
@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByProductId(UUID productId);

    Page<ProductVariant> findByProductIdAndEnabledTrueOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /**
     * Unfiltered definition list, used by the opt-in {@code includeDisabled} read of the global
     * branch. The store-scoped and search projections must keep their {@code enabled = true} filter:
     * this finder exists only so a soft-deleted definition can be re-enabled from the admin UI.
     */
    Page<ProductVariant> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /** Definition lookup scoped to its owning product, enabled only, for the purchase-order path. */
    Optional<ProductVariant> findByIdAndProductIdAndEnabledTrue(UUID id, UUID productId);

    // ─── Duplicate gates (D2 barcode, D3 product+variant name) ────────────

    boolean existsByBarCode(String barCode);

    boolean existsByBarCodeAndIdNot(String barCode, UUID id);

    boolean existsByProductIdAndVariantName(UUID productId, String variantName);

    boolean existsByProductIdAndVariantNameAndIdNot(UUID productId, String variantName, UUID id);

    /**
     * Whether the definition exists AND is still sellable.
     *
     * <p>The sales path must use this rather than {@code existsById}: the split removed
     * {@code ProductVariantRepository#findByIdForUpdate}, whose query filtered {@code enabled = true},
     * so a soft-deleted variant silently became sellable again.</p>
     */
    boolean existsByIdAndEnabledTrue(UUID id);

    // ─── Measure-unit type coherence (D4) ─────────────────────────────────

    /**
     * The distinct {@code unit_type}s already in use by the measure units of a product's variants,
     * used by the coherence guard of decision D4: a product cannot mix {@code PRODUCT} and {@code
     * SERVICE} units across its variants. Variants without a unit contribute nothing, because the
     * join onto {@code measure_units} drops a {@code NULL} foreign key — a catalog that is entirely
     * undeclared (the whole pre-V29 state) therefore constrains nothing.
     */
    @Query("""
            SELECT DISTINCT mu.unitType
            FROM ProductVariant pv
            JOIN MeasureUnit mu ON mu.id = pv.measureUnitId
            WHERE pv.productId = :productId
            """)
    List<MeasureUnitType> findUsedMeasureUnitTypesByProductId(@Param("productId") UUID productId);

    /**
     * Same read as {@link #findUsedMeasureUnitTypesByProductId(UUID)}, excluding one variant. The
     * update path uses it so the unit the variant already holds is not counted as somebody else's
     * and cannot reject itself. It is a second query instead of one with a nullable bind parameter:
     * PostgreSQL cannot infer the type of a bare {@code :excludedVariantId IS NULL} predicate.
     */
    @Query("""
            SELECT DISTINCT mu.unitType
            FROM ProductVariant pv
            JOIN MeasureUnit mu ON mu.id = pv.measureUnitId
            WHERE pv.productId = :productId
              AND pv.id <> :excludedVariantId
            """)
    List<MeasureUnitType> findUsedMeasureUnitTypesByProductIdExcludingVariantId(
            @Param("productId") UUID productId, @Param("excludedVariantId") UUID excludedVariantId);

    // ─── Store-scoped projections ─────────────────────────────────────────

    /**
     * Lists a product's enabled variants narrowed to one store, projecting the store prices and
     * stock into {@link ProductVariantResponse}. The constructor argument order matches the record
     * exactly; {@code sku} is the PRODUCT sku because the variant sku no longer exists (D2).
     */
    @Query("""
            SELECT new com.lifecontrol.api.product.dto.ProductVariantResponse(
                pv.id, pv.productId, pvss.companyStoreId, pv.barCode, p.sku,
                pv.variantName, pvss.listPrice, pvss.costPrice, pvss.stock, pv.enabled,
                pv.createdAt, pv.updatedAt, pv.measureUnitId)
            FROM ProductVariantStoreStock pvss
            JOIN ProductVariant pv ON pv.id = pvss.productVariantId
            JOIN Product p ON p.id = pv.productId
            WHERE pv.productId = :productId
              AND pvss.companyStoreId = :storeId
              AND pv.enabled = true
            ORDER BY pv.createdAt DESC
            """)
    Page<ProductVariantResponse> findStoreScopedByProductIdAndStoreId(
            @Param("productId") UUID productId, @Param("storeId") UUID storeId, Pageable pageable);

    /**
     * Searches the variants of one store by barcode, product sku, variant name or product name,
     * projecting the store prices and stock into {@link ProductVariantSearchResponse}. The
     * constructor argument order matches the record exactly.
     */
    @Query("""
            SELECT new com.lifecontrol.api.product.dto.ProductVariantSearchResponse(
                pv.id, pv.productId, pvss.companyStoreId, pv.barCode, p.sku,
                pv.variantName, pvss.listPrice, pvss.costPrice, pvss.stock, pv.enabled,
                p.name, p.sku, pv.createdAt, pv.updatedAt)
            FROM ProductVariantStoreStock pvss
            JOIN ProductVariant pv ON pv.id = pvss.productVariantId
            JOIN Product p ON p.id = pv.productId
            WHERE (pv.barCode = :query
               OR p.sku = :query
               OR LOWER(pv.variantName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(COALESCE(pv.barCode, '')) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(COALESCE(p.sku, '')) LIKE LOWER(CONCAT('%', :query, '%')))
            AND pvss.companyStoreId = :storeId
            AND pv.enabled = true
            ORDER BY pv.variantName
            """)
    Page<ProductVariantSearchResponse> searchByQuery(
            @Param("query") String query, @Param("storeId") UUID storeId, Pageable pageable);
}
