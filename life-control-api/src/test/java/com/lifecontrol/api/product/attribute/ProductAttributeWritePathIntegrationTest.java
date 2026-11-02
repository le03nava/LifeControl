package com.lifecontrol.api.product.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.product.attribute.exception.ProductAttributeValidationException;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.LinkedHashMap;
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
 * The write path's contract over real PostgreSQL: replace (L4), effective-set validation (S8) and the
 * level split (S6/S7), asserted through the services a client actually calls.
 *
 * <p>These are the probes an independent verification wrote to attack the change, kept because each one
 * is a case a unit test with a mocked catalog service cannot reach: it needs the real profile, the real
 * category tree and a real refusal. Two of them were the evidence for the defect the review found — a
 * required attribute skipped by omitting the map, and an incompatible stored set that made a product
 * unsavable — so they are the regression net for both, not illustrations.</p>
 */
@SpringBootTest
@WithMockUser(roles = "lc-admin")
@DisplayName("Product attribute write path — integration")
class ProductAttributeWritePathIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "pawp";
    private static final String COMPANY_KEY = "PAWP-ATTRIBUTE-WRITE-KEY";

    /** The {@code PRODUCT} unit {@code V3} seeds as "Pieza"; V55 makes a variant's unit required. */
    private static final String PIECE_UNIT_SAT_CODE = "H87";

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductVariantService productVariantService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID companyId;
    private UUID ferreteriaId;
    private UUID calzadoId;

    @BeforeEach
    void setUp() {
        cleanUp();
        // The id comes from the natural key (`TenantFixtures`' derivation) so the cleanup below can state
        // the tenant before the row exists — with a random id the leftover company of a previous method
        // would be unreachable, because `companies` is filtered by its own id.
        companyId = TenantFixtures.insertCompany(jdbc, COMPANY_KEY, "Attribute write probe company", "PAWP010101AB1");
        ferreteriaId = insertCategory("Ferretería");
        calzadoId = insertCategory("Calzado para Dama");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        // Inside the suite's tenant: an unscoped DELETE on an armed table matches no row, so the sweep
        // would leave the previous method's rows behind for `uq_product_categories_sibling_name`.
        TenantFixtures.inTenant(TenantFixtures.derivedId(COMPANY_KEY), () -> {
            jdbc.update("DELETE FROM product_variants WHERE bar_code LIKE ?", PREFIX + "%");
            jdbc.update("DELETE FROM products WHERE sku LIKE ?", PREFIX + "%");
            jdbc.update(
                    "DELETE FROM product_categories WHERE company_id IN (SELECT id FROM companies WHERE company_key = ?)",
                    COMPANY_KEY);
            jdbc.update("DELETE FROM companies WHERE company_key = ?", COMPANY_KEY);
        });
    }

    private UUID insertCategory(String name) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbc.update(
                        "INSERT INTO product_categories (id, company_id, parent_category_id, category_name, display_order, enabled)"
                                + " VALUES (?, ?, NULL, ?, 0, true)",
                        id,
                        companyId,
                        name));
        return id;
    }

    /**
     * A real {@code measure_units} row, read from the catalog rather than invented: since ADR-0006
     * decision 3 ({@code V55}) a variant declares a unit, and a random uuid would be refused by the
     * foreign key instead of exercising the rule under test.
     */
    private UUID pieceUnitId() {
        return jdbc.queryForObject("SELECT id FROM measure_units WHERE sat_code = ?", UUID.class, PIECE_UNIT_SAT_CODE);
    }

    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (var i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private ProductRequest productRequest(String sku, Map<String, Object> attributes, UUID categoryId) {
        return new ProductRequest(sku, "Probe " + sku, null, null, "GOODS", attributes, categoryId);
    }

    /**
     * A <b>creation</b> request: ADR-0006 decision 2 makes the variant list part of it, so every product this
     * suite creates declares its family instead of being written empty and given a variant afterwards.
     *
     * <p>{@code MULTI} and not {@code SIMPLE} because these cases add a second variant through the standalone
     * endpoint ({@code POST …/variants}), which is still reachable and still what most of them exercise — a
     * {@code SIMPLE} product would be refused by {@code trg_variant_matches_mode} on that second insert, and
     * this suite is about the attribute profile, not about the mode.</p>
     *
     * <p>The created variant declares the VARIANT-level entries the category requires, which is why the last
     * parameter exists: a shoe's {@code color} is required on the variant, and a creation that named none
     * would be refused by the same rule these cases are testing around.</p>
     */
    private ProductRequest creationRequest(
            String sku, Map<String, Object> attributes, UUID categoryId, Map<String, Object> variantAttributes) {
        return new ProductRequest(
                sku,
                "Probe " + sku,
                null,
                null,
                "GOODS",
                attributes,
                categoryId,
                Product.VARIANT_MODE_MULTI,
                List.of(new ProductVariantRequest(sku + "-CREATED", "CREADO", pieceUnitId(), variantAttributes)));
    }

    private Map<String, Object> calzadoSet() {
        return map("material_externo", "piel_vacuno", "tipo_ajuste", "agujeta", "genero", "dama");
    }

    /**
     * The services, run the way production runs them: the <b>caller</b> states the tenant.
     *
     * <p>Both services read {@code companies}, {@code product_categories} and
     * {@code product_attribute_profiles} inside their own transaction, and that boundary states nothing by
     * itself — production's caller is the controller, which runs behind the scope filter. With the pool on
     * the application role an unstated scope makes the company read answer empty and the service refuse with
     * {@code CompanyNotFoundException}: the same defect as an unscoped fixture read, one layer out.</p>
     */
    private <T> T asCompany(Supplier<T> call) {
        return TenantFixtures.inTenant(companyId, call);
    }

    @Test
    @DisplayName("S8: a required attribute cannot be skipped by omitting the map on a profiled category")
    void requiredAttributeCannotBeSkipped() {
        assertThatThrownBy(() -> asCompany(() -> productService.createProduct(
                        companyId, creationRequest(PREFIX + "-REQ", map(), ferreteriaId, null))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("S8: a stored NULL set is validated on update — a null map is not a bypass")
    void storedNullSetIsValidatedOnUpdate() {
        var created = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-NULL", map("material", "pvc"), ferreteriaId, null)));
        TenantFixtures.inTenant(
                companyId, () -> jdbc.update("UPDATE products SET attributes = NULL WHERE id = ?", created.id()));
        assertThatThrownBy(() -> asCompany(() -> productService.updateProduct(
                        companyId, created.id(), productRequest(PREFIX + "-NULL", null, ferreteriaId))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("S8: a valid stored set survives an update that sends no attributes")
    void validStoredSetSurvivesNullUpdate() {
        var created = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-KEEP", map("material", "pvc"), ferreteriaId, null)));

        assertThat(asCompany(() -> productService.updateProduct(
                                companyId, created.id(), productRequest(PREFIX + "-KEEP", null, ferreteriaId)))
                        .attributes())
                .containsEntry("material", "pvc");
    }

    @Test
    @DisplayName("L4: the request replaces the set; an omitted optional key is gone")
    void requestReplacesTheSet() {
        var created = asCompany(() -> productService.createProduct(
                companyId,
                creationRequest(PREFIX + "-REPLACE", map("material", "pvc", "marca", "ACME"), ferreteriaId, null)));

        assertThat(asCompany(() -> productService.updateProduct(
                                companyId,
                                created.id(),
                                productRequest(PREFIX + "-REPLACE", map("material", "cobre"), ferreteriaId)))
                        .attributes())
                .containsOnlyKeys("material");
    }

    @Test
    @DisplayName("S7: a VARIANT-level code is refused on the product, and a PRODUCT-level code on the variant")
    void levelIsEnforcedInBothDirections() {
        var withColor = calzadoSet();
        withColor.put("color", "negro");
        assertThatThrownBy(() -> asCompany(() -> productService.createProduct(
                        companyId, creationRequest(PREFIX + "-LVL-P", withColor, calzadoId, map("color", "negro")))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color")
                .hasMessageContaining("PRODUCT-level");

        var product = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-LVL", calzadoSet(), calzadoId, map("color", "negro"))));
        assertThatThrownBy(() -> asCompany(() -> productVariantService.createVariant(
                        companyId,
                        product.id(),
                        new ProductVariantRequest(
                                PREFIX + "-LVL-BC1", "26", pieceUnitId(), map("material_externo", "piel_vacuno")))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material_externo")
                .hasMessageContaining("VARIANT-level");

        var variantAttributes = asCompany(() -> productVariantService.createVariant(
                        companyId,
                        product.id(),
                        new ProductVariantRequest(PREFIX + "-LVL-BC2", "26", pieceUnitId(), map("color", "negro"))))
                .attributes();

        assertThat(variantAttributes).containsEntry("color", "negro");
    }

    @Test
    @DisplayName("S6/S8: a variant of a category that requires color cannot be created without it")
    void variantRequiresTheColorOfItsCategory() {
        var product = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-VREQ", calzadoSet(), calzadoId, map("color", "negro"))));

        assertThatThrownBy(() -> asCompany(() -> productVariantService.createVariant(
                        companyId,
                        product.id(),
                        new ProductVariantRequest(PREFIX + "-VREQ-BC", "26", pieceUnitId(), null))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("reclassifying with a null map is refused and leaves the row untouched")
    void reclassifyWithNullMapIsRefusedAndAtomic() {
        var created = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-MOVE", map("material", "pvc"), ferreteriaId, null)));

        assertThatThrownBy(() -> asCompany(() -> productService.updateProduct(
                        companyId, created.id(), productRequest(PREFIX + "-MOVE", null, calzadoId))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("attribute 'material' is not defined");

        var row = TenantFixtures.inTenant(
                companyId,
                () -> jdbc.queryForMap(
                        "SELECT category_id, attributes::text AS a FROM products WHERE id = ?", created.id()));
        assertThat(row.get("category_id")).isEqualTo(ferreteriaId);
        assertThat(String.valueOf(row.get("a"))).contains("pvc");
    }

    @Test
    @DisplayName("a branch with no profile accepts {} and refuses a null map that would keep the old set")
    void unprofiledBranchFollowsTheSameRule() {
        var unprofiled = insertCategory("Accesorios y Cuidado");
        var created = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-NOP", map("material", "pvc"), ferreteriaId, null)));

        assertThatThrownBy(() -> asCompany(() -> productService.updateProduct(
                        companyId, created.id(), productRequest(PREFIX + "-NOP", null, unprofiled))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material");

        assertThat(asCompany(() -> productService.updateProduct(
                                companyId, created.id(), productRequest(PREFIX + "-NOP", map(), unprofiled)))
                        .attributes())
                .isEmpty();
    }

    @Test
    @DisplayName("clearing the classification with a null map is refused, with {} it clears")
    void clearingTheClassification() {
        var created = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-NONE", map("material", "pvc"), ferreteriaId, null)));

        assertThatThrownBy(() -> asCompany(() -> productService.updateProduct(
                        companyId, created.id(), productRequest(PREFIX + "-NONE", null, null))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material");

        var cleared = asCompany(() ->
                productService.updateProduct(companyId, created.id(), productRequest(PREFIX + "-NONE", map(), null)));
        assertThat(cleared.attributes()).isEmpty();
        assertThat(cleared.categoryId()).isNull();
    }

    @Test
    @DisplayName("a reclassified product's variants keep the old branch's codes and then refuse edits that omit them")
    void variantsDriftAfterReclassification() {
        var product = asCompany(() -> productService.createProduct(
                companyId, creationRequest(PREFIX + "-DRIFT", calzadoSet(), calzadoId, map("color", "negro"))));
        var variant = asCompany(() -> productVariantService.createVariant(
                companyId,
                product.id(),
                new ProductVariantRequest(PREFIX + "-DRIFT-BC", "26", pieceUnitId(), map("color", "negro"))));

        asCompany(() -> productService.updateProduct(
                companyId, product.id(), productRequest(PREFIX + "-DRIFT", map("material", "pvc"), ferreteriaId)));

        assertThat(asCompany(() -> productVariantService.getVariant(companyId, product.id(), variant.id()))
                        .attributes())
                .containsEntry("color", "negro");
        assertThatThrownBy(() -> asCompany(() -> productVariantService.updateVariant(
                        companyId,
                        product.id(),
                        variant.id(),
                        new ProductVariantRequest(PREFIX + "-DRIFT-BC", "27", pieceUnitId(), null))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color");
    }
}
