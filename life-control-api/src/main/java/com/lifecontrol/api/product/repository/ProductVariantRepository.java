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

    /**
     * Definition lookup scoped to its owning product <b>and</b> its company, enabled only, for the
     * purchase-order path.
     *
     * <p>All three predicates are load-bearing and none of them implies another. The product says the
     * line names a variant of the product it also names; the company says the pair belongs to the
     * tenant that owns the order's store — nothing else does, because the order's store and the
     * line's catalog row are joined by no constraint at all; and {@code enabled} keeps a
     * soft-deleted definition unsellable. Dropping the company is what allowed a line to name
     * another tenant's variant while passing every other check.</p>
     *
     * <p>The company is required to be the store's, not the product's: {@code V40}'s composite
     * foreign keys already tie a variant to its own product's company, so asking with the product's
     * company would restate the schema and answer nothing about the order.</p>
     */
    Optional<ProductVariant> findByIdAndProductIdAndCompanyIdAndEnabledTrue(UUID id, UUID productId, UUID companyId);

    // ─── Duplicate gates (D2 barcode, D3 product+variant name) ────────────

    /**
     * Whether the barcode is already taken <b>inside one company</b>.
     *
     * <p>The variant belongs to a company (V39), and two tenants are separate businesses that
     * legitimately sell the same barcode, so the key that gates the write is
     * {@code (company_id, bar_code)} — derived here from the column, exactly like
     * {@code ProductRepository#existsBySkuAndCompanyId} does for the product SKU.</p>
     *
     * <p>Since {@code V40} the database carries the same key and {@code V56} made it partial —
     * {@code uq_variant_bar_code} on {@code (company_id, bar_code) WHERE bar_code IS NOT NULL}, which
     * replaced the full constraint V40 added over V14's global one — so this finder and the index agree, and
     * a cross-company repeat is accepted by both. The pair is deliberate: the finder answers 409 with the
     * offending value, and the index is what a writer bypassing this API runs into. A variant with no
     * barcode is outside both: the predicate leaves those rows unindexed, so an absent barcode here can
     * never be a conflict.</p>
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
     * How many <b>live</b> variants a product has, which is the count C1's delete guard reads.
     *
     * <p>{@code enabled = true} and not "every row": a variant is soft-deleted, so the disabled rows still
     * exist and counting them would let a product end up with zero sellable variants while the guard reported
     * a non-empty family. The guard ({@code ProductVariantService#deleteVariant}) asks this question and no
     * other, so the number it refuses on and the number the invariant is audited with have one shape.</p>
     */
    long countByProductIdAndEnabledTrue(UUID productId);

    /**
     * Whether the definition exists, is still sellable <b>and</b> belongs to {@code companyId}.
     *
     * <p>The sales path must use this rather than {@code existsById}: the split removed
     * {@code ProductVariantRepository#findByIdForUpdate}, whose query filtered {@code enabled = true},
     * so a soft-deleted variant silently became sellable again. And it must ask for a company: an
     * existence question by bare id — no product, no store, no company — is the weakest check this
     * repository can answer, and it accepts another tenant's variant as readily as its own. The
     * company here is the one that owns the order's store.</p>
     */
    boolean existsByIdAndCompanyIdAndEnabledTrue(UUID id, UUID companyId);

    /**
     * Definition lookup scoped to its company, enabled only, for the inventory engine.
     *
     * <p>The receipt and the sale deduction receive nothing but a variant id and a store id, so a
     * bare {@code findById} would let either of them move the stock of a variant that belongs to a
     * different tenant than the store does. The company is the one the store derives, and it is read
     * before the per-store lock: refusing after the lock would already be holding a row it should
     * never have touched.</p>
     */
    Optional<ProductVariant> findByIdAndCompanyIdAndEnabledTrue(UUID id, UUID companyId);

    // ─── Measure-unit type coherence (D4) ─────────────────────────────────

    /**
     * The distinct {@code unit_type}s already in use by the measure units of a product's variants,
     * used by the coherence guard of decision D4: a product cannot mix {@code PRODUCT} and {@code
     * SERVICE} units across its variants. Variants without a unit contribute nothing, because the
     * join onto {@code measure_units} drops a {@code NULL} foreign key — the query is unchanged by
     * {@code V55}, which simply leaves no row in that state once its report has been paid off.
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
