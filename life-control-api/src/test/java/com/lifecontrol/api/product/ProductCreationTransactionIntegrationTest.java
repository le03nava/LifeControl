package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.exception.BarcodeRequiredException;
import com.lifecontrol.api.product.exception.VariantModeMismatchException;
import com.lifecontrol.api.product.exception.VariantRemovalWouldEmptyProductException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * ADR-0006 decision 2 and invariant C1 over real PostgreSQL: a product is created <b>with</b> its
 * variants, in one transaction, and a variant refusal leaves nothing behind.
 *
 * <p>This is the suite the unit cases cannot be: "one transaction" is a claim about rows, so it is
 * asserted by counting them after each refusal instead of by verifying interactions on a mock. Every case
 * runs through the services a client calls ({@code ProductService#createProduct} and
 * {@code ProductVariantService#createVariant}), which is where the rules that refuse a variant already
 * live — the unit, the barcode-per-mode and the duplicate keys are not re-tested here, only the fact that
 * their refusal is <b>atomic with the product</b>.</p>
 *
 * <p>The pool connects as the application role, so every statement runs under the ADR-0005 policies and is
 * therefore tenant-scoped: the fixtures state their company ({@link TenantFixtures#inTenant}), and each
 * count below is read inside that same scope, because an unscoped read of an armed table answers zero rows
 * and would pass every assertion for the wrong reason.</p>
 */
@SpringBootTest
@WithMockUser(roles = "lc-admin")
@DisplayName("Product creation is one transaction — integration")
class ProductCreationTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Unique to this suite: the container is shared, so the prefix is how the cleanup finds its own rows. */
    private static final String PREFIX = "pct-";

    private static final String COMPANY_KEY = "PCT-CREATION-TRANSACTION-KEY";

    /** The {@code PRODUCT} unit {@code V3} seeds as "Pieza"; a variant's unit is required since {@code V55}. */
    private static final String PIECE_UNIT_SAT_CODE = "H87";

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductVariantService productVariantService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        cleanUp();
        // The id comes from the natural key (`TenantFixtures`' one derivation), so the cleanup can state the
        // tenant before the row exists: `companies` is filtered by its own id.
        companyId = TenantFixtures.insertCompany(jdbc, COMPANY_KEY, "Creation transaction probe", "PCT010101AB1");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /**
     * Deletes this suite's own rows, leaf-first and inside the tenant they belong to.
     *
     * <p>The variants are found <b>through their product</b> and not by barcode: a {@code SIMPLE} variant has
     * none since decision 4, so a barcode-prefixed sweep would leave exactly the rows the first case writes
     * and the next run would collide on {@code (product_id, variant_name)}.</p>
     */
    private void cleanUp() {
        var derivedId = TenantFixtures.derivedId(COMPANY_KEY);
        TenantFixtures.inTenant(derivedId, () -> {
            jdbc.update(
                    "DELETE FROM product_variants WHERE product_id IN" + " (SELECT id FROM products WHERE sku LIKE ?)",
                    PREFIX + "%");
            jdbc.update("DELETE FROM products WHERE sku LIKE ?", PREFIX + "%");
            jdbc.update("DELETE FROM companies WHERE company_key = ?", COMPANY_KEY);
        });
    }

    private UUID pieceUnitId() {
        return jdbc.queryForObject("SELECT id FROM measure_units WHERE sat_code = ?", UUID.class, PIECE_UNIT_SAT_CODE);
    }

    /**
     * Runs a service call the way production does: the <b>caller</b> states the tenant.
     *
     * <p>Each call is its own {@code REQUIRES_NEW} transaction on the fixture's boundary, so a refusal rolls
     * back everything the call wrote and the counts read afterwards are of committed state — which is the
     * only shape that can observe "the product was not written".</p>
     */
    private <T> T asCompany(Supplier<T> call) {
        return TenantFixtures.inTenant(companyId, call);
    }

    /** The same boundary for the call that answers nothing ({@code deleteVariant}). */
    private void asCompany(Runnable call) {
        TenantFixtures.inTenant(companyId, call);
    }

    private ProductVariantRequest variant(String barCode, String variantName) {
        return new ProductVariantRequest(barCode, variantName, pieceUnitId(), null);
    }

    private ProductRequest request(String sku, String variantMode, List<ProductVariantRequest> variants) {
        return new ProductRequest(sku, "Probe " + sku, null, null, "GOODS", null, null, variantMode, variants);
    }

    /** The variant rows a product owns, read through the tenant scope the policies require. */
    private List<Map<String, Object>> variantRows(UUID productId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbc.queryForList(
                        "SELECT variant_name, bar_code, measure_unit_id, enabled FROM product_variants"
                                + " WHERE product_id = ? ORDER BY variant_name",
                        productId));
    }

    /** How many product rows this suite's prefix has written, whatever became of their variants. */
    private int productRows(String sku) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbc.queryForObject("SELECT count(*) FROM products WHERE sku = ?", Integer.class, sku));
    }

    /**
     * C1's audit read: the number of products of this company that no variant belongs to.
     *
     * <p>It is the query the invariant is counted by (the ADR's "counted by a query"), so a refusal that
     * left a product without variants cannot pass by looking only at the variant count.</p>
     */
    private long productsWithoutVariants() {
        return TenantFixtures.inTenant(companyId, () -> productRepository.countProductsWithoutVariants(companyId));
    }

    @Test
    @DisplayName("decision 2: a SIMPLE product is created with its one variant, unit and no barcode")
    void simpleProductIsCreatedWithItsSingleVariant() {
        var created = asCompany(() -> productService.createProduct(
                companyId, request(PREFIX + "SIMPLE", null, List.of(variant(null, "Unica")))));

        assertThat(created.variantMode()).isEqualTo(Product.VARIANT_MODE_SIMPLE);
        assertThat(productRows(PREFIX + "SIMPLE")).isEqualTo(1);

        var rows = variantRows(created.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("variant_name")).isEqualTo("Unica");
        // Decision 4: a single-variant article has no barcode to give, and V56 left the column nullable.
        assertThat(rows.get(0).get("bar_code")).isNull();
        assertThat(rows.get(0).get("measure_unit_id")).isEqualTo(pieceUnitId());
        assertThat(rows.get(0).get("enabled")).isEqualTo(true);
        assertThat(productsWithoutVariants()).isZero();
    }

    @Test
    @DisplayName("decision 2: a MULTI product is created with every variant the request declares")
    void multiProductIsCreatedWithAllItsVariants() {
        var created = asCompany(() -> productService.createProduct(
                companyId,
                request(
                        PREFIX + "MULTI",
                        Product.VARIANT_MODE_MULTI,
                        List.of(variant(PREFIX + "MULTI-26", "Talla 26"), variant(PREFIX + "MULTI-27", "Talla 27")))));

        assertThat(created.variantMode()).isEqualTo(Product.VARIANT_MODE_MULTI);
        assertThat(productRows(PREFIX + "MULTI")).isEqualTo(1);

        var rows = variantRows(created.id());
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(row -> row.get("bar_code"))
                .containsExactly(PREFIX + "MULTI-26", PREFIX + "MULTI-27");
        assertThat(productsWithoutVariants()).isZero();
    }

    /**
     * {@code SIMPLE} means exactly one over real PostgreSQL too: the refusal names the mode and leaves no
     * row of any kind behind, which is asserted as counts and not as an interaction.
     */
    @Test
    @DisplayName("decision 2: a SIMPLE product with two variants is refused and no row is written")
    void simpleProductWithTwoVariantsWritesNothing() {
        var sku = PREFIX + "TWO";

        assertThatThrownBy(() -> asCompany(() -> productService.createProduct(
                        companyId,
                        request(
                                sku,
                                Product.VARIANT_MODE_SIMPLE,
                                List.of(variant(PREFIX + "TWO-A", "Chico"), variant(PREFIX + "TWO-B", "Grande"))))))
                .isInstanceOf(VariantModeMismatchException.class)
                .hasMessageContaining("variantMode")
                .hasMessageContaining(Product.VARIANT_MODE_SIMPLE);

        assertThat(productRows(sku)).isZero();
        assertThat(productsWithoutVariants()).isZero();
    }

    /**
     * The reason "creating one is one transaction" is a decision and not a detail: the variant service
     * refuses a {@code MULTI} variant with no barcode, and the product it was being created for must not
     * survive that refusal.
     */
    @Test
    @DisplayName("decision 4: a MULTI variant with no barcode is refused and the product is not written")
    void multiVariantWithoutBarcodeWritesNothing() {
        var sku = PREFIX + "NOBARCODE";

        assertThatThrownBy(() -> asCompany(() -> productService.createProduct(
                        companyId,
                        request(
                                sku,
                                Product.VARIANT_MODE_MULTI,
                                List.of(variant(PREFIX + "NOBARCODE-26", "Talla 26"), variant(null, "Sin codigo"))))))
                .isInstanceOf(BarcodeRequiredException.class)
                .hasMessageContaining("barCode");

        assertThat(productRows(sku)).isZero();
        assertThat(productsWithoutVariants()).isZero();
    }

    /**
     * The rollback the unit cases cannot observe: the measure unit is resolved <b>after</b> the product row
     * exists, so this is the case where a non-transactional creation would leave the product behind and a
     * transactional one leaves nothing.
     */
    @Test
    @DisplayName("a variant whose unit does not exist is refused and the product is not written")
    void unknownMeasureUnitWritesNothing() {
        var sku = PREFIX + "NOUNIT";
        var unknownUnit = UUID.randomUUID();

        assertThatThrownBy(() -> asCompany(() -> productService.createProduct(
                        companyId,
                        request(
                                sku,
                                Product.VARIANT_MODE_SIMPLE,
                                List.of(new ProductVariantRequest(null, "Sin unidad", unknownUnit, null))))))
                .isInstanceOf(MeasureUnitNotFoundException.class)
                .hasMessageContaining(unknownUnit.toString());

        assertThat(productRows(sku)).isZero();
        assertThat(productsWithoutVariants()).isZero();
    }

    /**
     * C1's write-path guard: a product never loses its last variant.
     *
     * <p>The variant definition is soft-deleted, so what the guard protects is the product's <b>live</b>
     * variant set; the audit query is asserted in the same case so the refusal and the invariant are read
     * from one state.</p>
     */
    @Test
    @DisplayName("C1: deleting the only variant of a product is refused")
    void deletingTheOnlyVariantIsRefused() {
        var created = asCompany(() -> productService.createProduct(
                companyId, request(PREFIX + "ONLY", null, List.of(variant(null, "Unica")))));
        var variantId = TenantFixtures.inTenant(
                companyId,
                () -> jdbc.queryForObject(
                        "SELECT id FROM product_variants WHERE product_id = ?", UUID.class, created.id()));

        assertThatThrownBy(
                        () -> asCompany(() -> productVariantService.deleteVariant(companyId, created.id(), variantId)))
                .isInstanceOf(VariantRemovalWouldEmptyProductException.class)
                .hasMessageContaining(created.id().toString())
                .hasMessageContaining("at least one variant");

        var rows = variantRows(created.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("enabled")).isEqualTo(true);
        // The product still has its variant, so the invariant the guard protects still holds.
        assertThat(productsWithoutVariants()).isZero();
    }
}
