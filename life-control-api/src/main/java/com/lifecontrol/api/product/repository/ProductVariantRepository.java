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

    /**
     * Whether the barcode is already taken <b>inside one company</b>.
     *
     * <p>The variant belongs to a company (V39), and two tenants are separate businesses that
     * legitimately sell the same barcode, so the key that gates the write is
     * {@code (company_id, bar_code)} — derived here from the column, exactly like
     * {@code ProductRepository#existsBySkuAndCompanyId} does for the product SKU.</p>
     *
     * <p>Until the contract step lands this is a <b>service</b> rule only: the database still carries
     * V14's global {@code uk_product_variants_bar_code}, so a live insert of the same barcode in two
     * companies is refused by the index itself. Replacing that index with
     * {@code UNIQUE (company_id, bar_code)} is the contract step and nothing in this interface can
     * make the database agree sooner.</p>
     */
    boolean existsByBarCodeAndCompanyId(String barCode, UUID companyId);

    /**
     * The update half of {@link #existsByBarCodeAndCompanyId(String, UUID)}: the same key, excluding
     * the variant being written so it cannot conflict with itself.
     */
    boolean existsByBarCodeAndCompanyIdAndIdNot(String barCode, UUID companyId, UUID id);

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
     *
     * <p>The company is a <b>predicate</b> on the joined product, not something the caller is trusted
     * to have checked: the store in the query string is only authorizing, and the rows the store join
     * reaches are rows of whatever tenant wrote them. The company passed here is the one the store
     * itself belongs to, so a store of another tenant cannot be used to read this product's variants.</p>
     */
    @Query("""
            SELECT new com.lifecontrol.api.product.dto.ProductVariantResponse(
                pv.id, pv.productId, pvss.companyStoreId, pv.barCode, p.sku,
                pv.variantName, pvss.listPrice, pvss.costPrice, pvss.stock, pv.enabled,
                pv.createdAt, pv.updatedAt, pv.measureUnitId, pv.attributes)
            FROM ProductVariantStoreStock pvss
            JOIN ProductVariant pv ON pv.id = pvss.productVariantId
            JOIN Product p ON p.id = pv.productId
            WHERE pv.productId = :productId
              AND pvss.companyStoreId = :storeId
              AND p.company.id = :companyId
              AND pv.enabled = true
            ORDER BY pv.createdAt DESC
            """)
    Page<ProductVariantResponse> findStoreScopedByProductIdAndStoreId(
            @Param("companyId") UUID companyId,
            @Param("productId") UUID productId,
            @Param("storeId") UUID storeId,
            Pageable pageable);

    /**
     * Searches the variants of one store by barcode, product sku, variant name or product name,
     * projecting the store prices and stock into {@link ProductVariantSearchResponse}. The
     * constructor argument order matches the record exactly.
     *
     * <p>The company is the tenant that owns the queried store, and it is a <b>predicate</b>: the store
     * grant answers who may read the store, never which tenant the rows joined onto it belong to, and
     * the store join reaches whichever variant a per-store row names. A post-filter would be the same
     * rule written where the leak already happened, so it is written here, in the query.</p>
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
            AND p.company.id = :companyId
            AND pv.enabled = true
            ORDER BY pv.variantName
            """)
    Page<ProductVariantSearchResponse> searchByQuery(
            @Param("companyId") UUID companyId,
            @Param("query") String query,
            @Param("storeId") UUID storeId,
            Pageable pageable);
}
