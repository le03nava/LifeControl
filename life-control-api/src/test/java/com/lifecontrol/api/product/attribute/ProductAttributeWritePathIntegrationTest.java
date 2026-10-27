package com.lifecontrol.api.product.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.product.attribute.exception.ProductAttributeValidationException;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
@DisplayName("Product attribute write path — integration")
class ProductAttributeWritePathIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "pawp";
    private static final String COMPANY_KEY = "PAWP-ATTRIBUTE-WRITE-KEY";

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
        companyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, rfc, enabled) VALUES (?, ?, ?, ?, true)",
                companyId,
                COMPANY_KEY,
                "Attribute write probe company",
                "PAWP010101AB1");
        ferreteriaId = insertCategory("Ferretería");
        calzadoId = insertCategory("Calzado para Dama");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM product_variants WHERE bar_code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", PREFIX + "%");
        jdbc.update(
                "DELETE FROM product_categories WHERE company_id IN (SELECT id FROM companies WHERE company_key = ?)",
                COMPANY_KEY);
        jdbc.update("DELETE FROM companies WHERE company_key = ?", COMPANY_KEY);
    }

    private UUID insertCategory(String name) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_categories (id, company_id, parent_category_id, category_name, display_order, enabled)"
                        + " VALUES (?, ?, NULL, ?, 0, true)",
                id,
                companyId,
                name);
        return id;
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

    private Map<String, Object> calzadoSet() {
        return map("material_externo", "piel_vacuno", "tipo_ajuste", "agujeta", "genero", "dama");
    }

    @Test
    @DisplayName("S8: a required attribute cannot be skipped by omitting the map on a profiled category")
    void requiredAttributeCannotBeSkipped() {
        assertThatThrownBy(() -> productService.createProduct(productRequest(PREFIX + "-REQ", map(), ferreteriaId)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("S8: a stored NULL set is validated on update — a null map is not a bypass")
    void storedNullSetIsValidatedOnUpdate() {
        var created =
                productService.createProduct(productRequest(PREFIX + "-NULL", map("material", "pvc"), ferreteriaId));
        jdbc.update("UPDATE products SET attributes = NULL WHERE id = ?", created.id());

        assertThatThrownBy(() -> productService.updateProduct(
                        created.id(), productRequest(PREFIX + "-NULL", null, ferreteriaId)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("S8: a valid stored set survives an update that sends no attributes")
    void validStoredSetSurvivesNullUpdate() {
        var created =
                productService.createProduct(productRequest(PREFIX + "-KEEP", map("material", "pvc"), ferreteriaId));

        assertThat(productService
                        .updateProduct(created.id(), productRequest(PREFIX + "-KEEP", null, ferreteriaId))
                        .attributes())
                .containsEntry("material", "pvc");
    }

    @Test
    @DisplayName("L4: the request replaces the set; an omitted optional key is gone")
    void requestReplacesTheSet() {
        var created = productService.createProduct(
                productRequest(PREFIX + "-REPLACE", map("material", "pvc", "marca", "ACME"), ferreteriaId));

        assertThat(productService
                        .updateProduct(
                                created.id(),
                                productRequest(PREFIX + "-REPLACE", map("material", "cobre"), ferreteriaId))
                        .attributes())
                .containsOnlyKeys("material");
    }

    @Test
    @DisplayName("S7: a VARIANT-level code is refused on the product, and a PRODUCT-level code on the variant")
    void levelIsEnforcedInBothDirections() {
        var withColor = calzadoSet();
        withColor.put("color", "negro");
        assertThatThrownBy(() -> productService.createProduct(productRequest(PREFIX + "-LVL-P", withColor, calzadoId)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color")
                .hasMessageContaining("PRODUCT-level");

        var product = productService.createProduct(productRequest(PREFIX + "-LVL", calzadoSet(), calzadoId));
        assertThatThrownBy(() -> productVariantService.createVariant(
                        product.id(),
                        new ProductVariantRequest(
                                PREFIX + "-LVL-BC1", "26", null, map("material_externo", "piel_vacuno"))))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material_externo")
                .hasMessageContaining("VARIANT-level");

        assertThat(productVariantService
                        .createVariant(
                                product.id(),
                                new ProductVariantRequest(PREFIX + "-LVL-BC2", "26", null, map("color", "negro")))
                        .attributes())
                .containsEntry("color", "negro");
    }

    @Test
    @DisplayName("S6/S8: a variant of a category that requires color cannot be created without it")
    void variantRequiresTheColorOfItsCategory() {
        var product = productService.createProduct(productRequest(PREFIX + "-VREQ", calzadoSet(), calzadoId));

        assertThatThrownBy(() -> productVariantService.createVariant(
                        product.id(), new ProductVariantRequest(PREFIX + "-VREQ-BC", "26", null, null)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("reclassifying with a null map is refused and leaves the row untouched")
    void reclassifyWithNullMapIsRefusedAndAtomic() {
        var created =
                productService.createProduct(productRequest(PREFIX + "-MOVE", map("material", "pvc"), ferreteriaId));

        assertThatThrownBy(() ->
                        productService.updateProduct(created.id(), productRequest(PREFIX + "-MOVE", null, calzadoId)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("attribute 'material' is not defined");

        var row =
                jdbc.queryForMap("SELECT category_id, attributes::text AS a FROM products WHERE id = ?", created.id());
        assertThat(row.get("category_id")).isEqualTo(ferreteriaId);
        assertThat(String.valueOf(row.get("a"))).contains("pvc");
    }

    @Test
    @DisplayName("a branch with no profile accepts {} and refuses a null map that would keep the old set")
    void unprofiledBranchFollowsTheSameRule() {
        var unprofiled = insertCategory("Accesorios y Cuidado");
        var created =
                productService.createProduct(productRequest(PREFIX + "-NOP", map("material", "pvc"), ferreteriaId));

        assertThatThrownBy(() ->
                        productService.updateProduct(created.id(), productRequest(PREFIX + "-NOP", null, unprofiled)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material");

        assertThat(productService
                        .updateProduct(created.id(), productRequest(PREFIX + "-NOP", map(), unprofiled))
                        .attributes())
                .isEmpty();
    }

    @Test
    @DisplayName("clearing the classification with a null map is refused, with {} it clears")
    void clearingTheClassification() {
        var created =
                productService.createProduct(productRequest(PREFIX + "-NONE", map("material", "pvc"), ferreteriaId));

        assertThatThrownBy(
                        () -> productService.updateProduct(created.id(), productRequest(PREFIX + "-NONE", null, null)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("material");

        var cleared = productService.updateProduct(created.id(), productRequest(PREFIX + "-NONE", map(), null));
        assertThat(cleared.attributes()).isEmpty();
        assertThat(cleared.categoryId()).isNull();
    }

    @Test
    @DisplayName("a reclassified product's variants keep the old branch's codes and then refuse edits that omit them")
    void variantsDriftAfterReclassification() {
        var product = productService.createProduct(productRequest(PREFIX + "-DRIFT", calzadoSet(), calzadoId));
        var variant = productVariantService.createVariant(
                product.id(), new ProductVariantRequest(PREFIX + "-DRIFT-BC", "26", null, map("color", "negro")));

        productService.updateProduct(
                product.id(), productRequest(PREFIX + "-DRIFT", map("material", "pvc"), ferreteriaId));

        assertThat(productVariantService.getVariant(product.id(), variant.id()).attributes())
                .containsEntry("color", "negro");
        assertThatThrownBy(() -> productVariantService.updateVariant(
                        product.id(), variant.id(), new ProductVariantRequest(PREFIX + "-DRIFT-BC", "27", null, null)))
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("color");
    }
}
