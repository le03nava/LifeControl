package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S4 of {@code odd/tasks/cfdi-fiscal-document.md} and L6(e) of the same record:
 * {@code V104__product_sat_code_format.sql} constrains {@code products.sat_code} the way the category's key
 * has been constrained since {@code V34}.
 *
 * <p><b>Why this column and not the other two keys.</b> The measure unit's key is {@code NOT NULL UNIQUE}
 * since {@code V1} and the category's has had {@code ck_product_categories_sat_code_format} since
 * {@code V34}; the product's has been twenty characters of free text with no check and no catalog, and the
 * concept is the one element a fiscal document cannot omit. This suite pins the two halves that fix it: the
 * request refuses a malformed key with a 400 that names the field, and the <b>constraint</b> refuses it for
 * every writer that bypasses the API — the second half matters because the column has been writable free text
 * since {@code V1}, so the API is not the only writer that ever existed.</p>
 *
 * <p><b>Why the raw-SQL cases are here and not a service unit test.</b> A rule the database states can only
 * be observed by writing to the database: {@code ck_products_sat_code_format} is what makes an eight-digit
 * key true of the table rather than of one code path, and the insert below states the tenant the way the pool
 * needs because {@code products} is armed by {@code V53}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The product's fiscal key format (S4)")
class ProductSatCodeFormatIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PRODUCT-SAT-CODE-FORMAT-KEY";
    private static final String COMPANY_RFC = "PSAT010101AB1";
    private static final String SKU_PREFIX = "PSAT-";

    /** The {@code PRODUCT} unit {@code V3} seeds as "Pieza"; a variant's unit is required since {@code V55}. */
    private static final String PIECE_UNIT_SAT_CODE = "H87";

    /** A well-formed SAT {@code ClaveProdServ}, so the accepted case is told apart from a literal. */
    private static final String VALID_KEY = "27111600";

    /** Seven digits: the shape the category's constraint already refuses, and this one now does too. */
    private static final String SHORT_KEY = "2711160";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        cleanUp();
        companyId = TenantFixtures.insertCompany(
                jdbcTemplate, COMPANY_KEY, "SAT code format probe", COMPANY_RFC, TenantFixtures.SEED_CURRENCY);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // --- the request half ---

    @Test
    @DisplayName("a product with a non-eight-digit key is a 400 that names the field, and stores nothing")
    void aMalformedKeyIsRefusedNamingTheField() throws Exception {
        mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU_PREFIX + "SHORT", SHORT_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.satCode").value(containsString("eight digits")));

        assertThat(countProducts(SKU_PREFIX + "SHORT"))
                .as("and nothing was stored: the shape is refused at the boundary, not by the constraint after "
                        + "the work was done")
                .isZero();
    }

    @Test
    @DisplayName("a product with an eight-digit key is accepted, and the key comes back")
    void anEightDigitKeyIsAccepted() throws Exception {
        mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU_PREFIX + "VALID", VALID_KEY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.satCode").value(VALID_KEY));

        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT sat_code FROM products WHERE sku = ?", String.class, SKU_PREFIX + "VALID")))
                .as("the key the caller stated is the key the row carries. Read inside the tenant, because "
                        + "`products` is armed: an unscoped read answers zero rows and would pass over nothing")
                .isEqualTo(VALID_KEY);
    }

    @Test
    @DisplayName("a product with no key and a blank key are both still savable, with null stored")
    void anAbsentKeyIsStillSavable() throws Exception {
        mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU_PREFIX + "ABSENT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.satCode").value(nullValue()));

        mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU_PREFIX + "BLANK", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.satCode").value(nullValue()));

        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM products WHERE sku LIKE ? AND sat_code IS NULL",
                                Integer.class,
                                SKU_PREFIX + "%")))
                .as("optional but validated, exactly like the category's key since V34: a blank value is "
                        + "normalized to NULL rather than reaching the column as an empty string")
                .isEqualTo(2);
    }

    // --- the database half ---

    @Test
    @DisplayName("the constraint refuses a malformed key written by raw SQL, and admits null and eight digits")
    void theConstraintRefusesAMalformedKeyForEveryWriter() {
        assertThatThrownBy(() -> insertProductDirectly(UUID.randomUUID(), SKU_PREFIX + "RAWSQL-BAD", "ABC123"))
                .as("ck_products_sat_code_format judges every writer, which is the whole point of stating the "
                        + "rule in the schema and not only in the request")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_products_sat_code_format");

        var nullKey = UUID.randomUUID();
        insertProductDirectly(nullKey, SKU_PREFIX + "RAWSQL-NULL", null);
        var validKey = UUID.randomUUID();
        insertProductDirectly(validKey, SKU_PREFIX + "RAWSQL-VALID", VALID_KEY);

        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM products WHERE sku LIKE ?",
                                Integer.class,
                                SKU_PREFIX + "RAWSQL-%")))
                .as("two rows landed: the optional value and the eight-digit one")
                .isEqualTo(2);
    }

    // --- fixtures ---

    private void insertProductDirectly(UUID id, String sku, String satCode) {
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO products (id, sku, name, sat_code, company_id, tax_code) VALUES (?, ?, 'SAT probe', ?, ?, 'NA')",
                        id,
                        sku,
                        satCode,
                        companyId));
    }

    private int countProducts(String sku) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM products WHERE sku = ?", Integer.class, sku);
    }

    private void cleanUp() {
        var derivedId = TenantFixtures.derivedId(COMPANY_KEY);
        TenantFixtures.inTenant(derivedId, () -> {
            jdbcTemplate.update(
                    "DELETE FROM product_variants WHERE product_id IN (SELECT id FROM products WHERE sku LIKE ?)",
                    SKU_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM products WHERE sku LIKE ?", SKU_PREFIX + "%");
            jdbcTemplate.update(
                    "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?",
                    derivedId);
            jdbcTemplate.update(
                    "UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", derivedId);
            jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", derivedId);
            jdbcTemplate.update("DELETE FROM posting_rule_line WHERE company_id = ?", derivedId);
            jdbcTemplate.update("DELETE FROM posting_rule WHERE company_id = ?", derivedId);
            jdbcTemplate.update("DELETE FROM account WHERE company_id = ?", derivedId);
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", derivedId);
        });
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"));
    }

    /**
     * A creation body with one {@code SIMPLE} variant — ADR-0006 decision 2 makes the variant part of the
     * creation — and the fiscal key stated only when the case states one.
     */
    private String body(String sku, String satCode) {
        var unitId = jdbcTemplate.queryForObject(
                "SELECT id FROM measure_units WHERE sat_code = ?", UUID.class, PIECE_UNIT_SAT_CODE);
        var json = "{\"sku\":\"" + sku + "\",\"name\":\"SAT probe\"";
        if (satCode != null) {
            json += ",\"satCode\":\"" + satCode + "\"";
        }
        // The barcode is the SKU and not null: `SIMPLE` makes it optional (ADR-0006 decision 4) but the tree
        // refuses a second variant with no barcode in the same company, so two creations in one case each need
        // their own value — a fact of the variant contract, not of the key this suite is about.
        return json + ",\"variants\":[{\"barCode\":\"" + sku + "\",\"variantName\":\"Unica\"," + "\"measureUnitId\":\""
                + unitId + "\"}]}";
    }
}
